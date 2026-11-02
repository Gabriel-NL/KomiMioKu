"""Production index SQL: persistence, collection/root isolation, pruning and reading states."""
from pathlib import Path
import re
import sqlite3
import unittest
from test_local_manga_tags_schema import SQL_ROOT, MANGAS, QUERIES as MANGA_QUERIES

parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", (SQL_ROOT / "data/localCollectionIndex.sq").read_text(encoding="utf-8"))
DDL = parts[0]
Q = dict(zip(parts[1::2], parts[2::2]))

class PersistentIndexTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript(MANGAS + DDL)

    def put(self, source=0, root="root", url="Book", genre="Tag", fingerprint="v1"):
        values = dict(source_id=source, root_uri=root, url=url, title="Title", author="Author", artist=None,
                      description="Description", genre=genre, status=1, thumbnail=None, modified=20, fingerprint=fingerprint)
        self.db.execute(Q["putEntry"], values)

    def rows(self, source=0, root="root"):
        return self.db.execute(Q["getEntries"], dict(source_id=source, root_uri=root)).fetchall()

    def register(self, url, source=0):
        values = dict(source_id=source, url=url, title=url)
        self.db.execute(MANGA_QUERIES["ensureLocalManga"], values)
        return self.db.execute(MANGA_QUERIES["getLocalMangaId"], values).fetchone()[0]

    def test_same_path_is_isolated_by_collection_and_root(self):
        self.put()
        self.put(source=2000000000001, genre="Other collection")
        self.put(root="Other root", genre="Other folder selection")
        self.assertEqual(1, len(self.rows()))
        self.assertEqual("Tag", self.rows()[0][7])
        self.assertEqual("Other collection", self.rows(2000000000001)[0][7])
        self.assertEqual("Other folder selection", self.rows(root="Other root")[0][7])

    def test_ready_marker_distinguishes_empty_collection_from_first_load(self):
        values = dict(source_id=0, root_uri="root")
        self.assertEqual(0, self.db.execute(Q["isReady"], values).fetchone()[0])
        self.db.execute(Q["markReady"], values)
        self.assertEqual(1, self.db.execute(Q["isReady"], values).fetchone()[0])
        self.assertEqual([], self.rows())

    def test_edit_replaces_only_one_index_entry_and_preserves_manga_history(self):
        self.put(url="Book")
        self.put(url="Keep")
        manga = self.register("Book")
        self.db.execute("UPDATE mangas SET favorite=1 WHERE _id=?", (manga,))
        self.db.execute("INSERT INTO chapters VALUES(1, ?, 1, 7)", (manga,))
        self.put(url="Book", genre="Edited", fingerprint="v2")
        self.assertEqual(["Edited", "Tag"], [row[7] for row in self.rows()])
        self.assertEqual(1, self.db.execute("SELECT favorite FROM mangas WHERE _id=?", (manga,)).fetchone()[0])
        self.assertEqual([(1, manga, 1, 7)], self.db.execute("SELECT * FROM chapters").fetchall())

    def test_pruning_only_removes_index_rows_for_that_root(self):
        self.put(url="Gone")
        self.put(url="Keep")
        self.put(root="other", url="Gone")
        manga = self.register("Gone")
        self.db.execute(Q["removeEntry"], dict(source_id=0, root_uri="root", url="Gone"))
        self.assertEqual(["Keep"], [row[2] for row in self.rows()])
        self.assertEqual(1, len(self.rows(root="other")))
        self.assertIsNotNone(self.db.execute("SELECT _id FROM mangas WHERE _id=?", (manga,)).fetchone())
        self.db.execute(Q["removeEntry"], dict(source_id=0, root_uri="root", url="Keep"))
        self.assertEqual([], self.rows())

    def test_batch_reading_matches_unread_started_finished_and_scope(self):
        unread = self.register("Unread")
        started = self.register("Started")
        finished = self.register("Finished")
        self.register("Remote", 999)
        self.db.execute("INSERT INTO chapters VALUES(1, ?, 0, 3)", (started,))
        self.db.execute("INSERT INTO chapters VALUES(2, ?, 1, 0)", (finished,))
        self.db.execute("INSERT INTO chapters VALUES(3, ?, 1, 0)", (finished,))
        self.assertEqual(dict(Unread="unread", Started="started", Finished="finished"), dict(self.db.execute(Q["readingStates"], dict(source_id=0))))

    def test_migration_52_adds_index_without_modifying_old_rows(self):
        self.db.executescript("DROP TABLE local_collection_index; DROP TABLE local_collection_index_roots;")
        manga = self.register("Keep")
        before = self.db.execute("SELECT * FROM mangas").fetchall()
        self.db.executescript((SQL_ROOT / "migrations/52.sqm").read_text(encoding="utf-8"))
        self.assertEqual(before, self.db.execute("SELECT * FROM mangas").fetchall())
        self.assertEqual([], self.rows())
