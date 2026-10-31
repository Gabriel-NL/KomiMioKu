"""Regressions for distinct file sources sharing a native database."""
from pathlib import Path
import re
import sqlite3
import unittest
from test_local_manga_tags_schema import ROOT, SQL_ROOT, MANGAS, TAG_DDL, TAG_INSERT, DDL, QUERIES

SOURCE_SQL = (SQL_ROOT / "data/localSourceTags.sq").read_text(encoding="utf-8")
parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", SOURCE_SQL)
SOURCE_DDL = parts[0]
SOURCE_QUERIES = dict(zip(parts[1::2], parts[2::2]))
PRIVATE = 1000000000001


class FileSourceIsolationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript(MANGAS + TAG_DDL + DDL + SOURCE_DDL)

    def register(self, source, tags, url="Same folder"):
        values = {"source_id": source, "url": url, "title": "Title"}
        self.db.execute(QUERIES["ensureLocalManga"], values)
        manga = self.db.execute(QUERIES["getLocalMangaId"], values).fetchone()[0]
        self.replace(manga, source, tags)
        return manga

    def replace(self, manga, source, tags):
        self.db.execute(QUERIES["clearMangaTags"], {"manga_id": manga})
        for tag in tags:
            values = {"name": tag, "normalized_name": tag.lower(), "source_id": source, "manga_id": manga}
            self.db.execute(TAG_INSERT, values)
            self.db.execute(SOURCE_QUERIES["insertSourceTag"], values)
            self.db.execute(QUERIES["insertMangaTag"], values)

    def catalog(self, source):
        return self.db.execute(SOURCE_QUERIES["getSourceTags"], {"source_id": source}).fetchall()

    def manga_tags(self, manga):
        return self.db.execute(QUERIES["getTagsForManga"], {"manga_id": manga}).fetchall()

    def test_same_folder_names_have_distinct_manga_ids_and_progress(self):
        local = self.register(0, ["Local"])
        private = self.register(PRIVATE, ["Private"])
        self.assertNotEqual(local, private)
        self.db.execute("UPDATE mangas SET favorite=1 WHERE _id=?", (local,))
        self.db.execute("INSERT INTO chapters VALUES(1, ?, 1, 9)", (local,))
        self.register(PRIVATE, ["Changed"])
        self.assertEqual(1, self.db.execute("SELECT favorite FROM mangas WHERE _id=?", (local,)).fetchone()[0])
        self.assertEqual(0, self.db.execute("SELECT favorite FROM mangas WHERE _id=?", (private,)).fetchone()[0])
        self.assertEqual([(1, local, 1, 9)], self.db.execute("SELECT * FROM chapters").fetchall())

    def test_catalogs_are_scoped_even_when_tags_share_an_id(self):
        self.register(0, ["Shared", "Local"])
        self.register(PRIVATE, ["Shared", "Private"])
        self.assertEqual(["Local", "Shared"], [row[1] for row in self.catalog(0)])
        self.assertEqual(["Private", "Shared"], [row[1] for row in self.catalog(PRIVATE)])
        self.assertEqual(self.catalog(0)[1][0], self.catalog(PRIVATE)[1][0])

    def test_editing_private_links_preserves_local_links(self):
        local = self.register(0, ["Adventure"])
        private = self.register(PRIVATE, ["Romance"])
        before = self.manga_tags(local)
        self.replace(private, PRIVATE, [])
        self.assertEqual(before, self.manga_tags(local))
        self.assertEqual([], self.manga_tags(private))

    def test_alias_merging_keeps_both_source_catalogs(self):
        self.register(0, ["Old"])
        self.register(PRIVATE, ["Old", "New"])
        old, target = [self.db.execute("SELECT id FROM local_tags WHERE name=?", (name,)).fetchone()[0] for name in ["Old", "New"]]
        self.db.execute(SOURCE_QUERIES["mergeSourceTags"], {"target_id": target, "old_id": old})
        self.db.execute(QUERIES["mergeTagLinks"], {"target_id": target, "old_id": old})
        self.db.execute("DELETE FROM local_tags WHERE id=?", (old,))
        self.assertEqual(["New"], [row[1] for row in self.catalog(0)])
        self.assertEqual(["New"], [row[1] for row in self.catalog(PRIVATE)])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_migration_preserves_original_rows_and_does_not_assign_them_to_private(self):
        with sqlite3.connect(":memory:") as db:
            db.executescript(MANGAS + TAG_DDL + DDL)
            db.execute(TAG_INSERT, {"name": "Historical", "normalized_name": "historical"})
            db.execute(QUERIES["ensureLocalManga"], {"source_id": 0, "url": "Old", "title": "Old"})
            before = db.execute("SELECT * FROM mangas").fetchall()
            db.executescript((SQL_ROOT / "migrations/50.sqm").read_text(encoding="utf-8"))
            self.assertEqual(before, db.execute("SELECT * FROM mangas").fetchall())
            self.assertEqual(1, len(db.execute(SOURCE_QUERIES["getSourceTags"], {"source_id": 0}).fetchall()))
            self.assertEqual([], db.execute(SOURCE_QUERIES["getSourceTags"], {"source_id": PRIVATE}).fetchall())

    def test_persistent_source_constant_matches_sql_scope(self):
        ids = (ROOT / "domain/src/main/java/tachiyomi/domain/source/model/FileSourceIds.kt").read_text(encoding="utf-8")
        self.assertIn("PRIVATE_COLLECTION = " + str(PRIVATE) + "L", ids)
        self.assertIn(str(PRIVATE), QUERIES["getFileMangaSource"])


if __name__ == "__main__":
    unittest.main()
