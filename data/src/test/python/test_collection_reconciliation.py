"""Execute production reconciliation SQL against linked manga records."""
import re
import unittest
import test_file_source_isolation as fixtures

SQL_ROOT = fixtures.SQL_ROOT

parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", (SQL_ROOT / "data/fileReconciliation.sq").read_text(encoding="utf-8"))
QUERIES = dict(zip(parts[1::2], parts[2::2]))
TARGET = 2000000456789


class ReconciliationTest(unittest.TestCase):
    setUp = fixtures.FileSourceIsolationTest.setUp
    register = fixtures.FileSourceIsolationTest.register
    replace = fixtures.FileSourceIsolationTest.replace
    catalog = fixtures.FileSourceIsolationTest.catalog
    manga_tags = fixtures.FileSourceIsolationTest.manga_tags

    def test_move_preserves_identity_favorite_chapters_and_tag_links(self):
        manga = self.register(0, ["Tag"], "Book")
        self.db.execute("UPDATE mangas SET favorite=1 WHERE _id=?", (manga,))
        self.db.execute("INSERT INTO chapters VALUES(1, ?, 1, 9)", (manga,))
        before = self.manga_tags(manga)
        params = dict(target_source=TARGET, manga_id=manga, old_source=0, url="Book")
        self.db.execute(QUERIES["moveRecord"], params)
        self.db.execute(QUERIES["copyCatalog"], params)
        self.assertEqual((TARGET, 1), self.db.execute("SELECT source,favorite FROM mangas WHERE _id=?", (manga,)).fetchone())
        self.assertEqual([(1, manga, 1, 9)], self.db.execute("SELECT * FROM chapters").fetchall())
        self.assertEqual(before, self.manga_tags(manga))
        self.assertEqual(["Tag"], [row[1] for row in self.catalog(TARGET)])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_existing_destination_and_changed_origin_are_guarded(self):
        manga = self.register(0, ["Old"], "Book")
        self.register(TARGET, ["New"], "Book")
        params = dict(target_source=TARGET, manga_id=manga, old_source=0, url="Book")
        self.assertEqual(0, self.db.execute(QUERIES["moveRecord"], params).rowcount)
        params["url"] = "Renamed"
        self.assertEqual(0, self.db.execute(QUERIES["moveRecord"], params).rowcount)
        self.assertEqual(0, self.db.execute("SELECT source FROM mangas WHERE _id=?", (manga,)).fetchone()[0])

    def test_reconnected_custom_source_can_replace_tags(self):
        manga = self.register(0, ["Old"], "Book")
        self.db.execute(QUERIES["moveRecord"], dict(target_source=TARGET, manga_id=manga, old_source=0, url="Book"))
        self.replace(manga, TARGET, ["New"])
        self.assertEqual(["New"], [row[1] for row in self.manga_tags(manga)])
        actual = fixtures.QUERIES["getFileMangaSource"]
        self.assertEqual(TARGET, self.db.execute(actual, dict(manga_id=manga)).fetchone()[0])
