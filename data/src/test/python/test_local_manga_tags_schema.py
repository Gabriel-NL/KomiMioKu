"""SQLite integration checks using the actual relation schema, migration and queries."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[4]
SQL_ROOT = ROOT / "data/src/main/sqldelight/tachiyomi"
TAG_SQL = (SQL_ROOT / "data/localTags.sq").read_text(encoding="utf-8")
TAG_DDL, TAG_QUERIES = TAG_SQL.split("getAllTags:", 1)
TAG_INSERT = TAG_QUERIES.split("insertTag:", 1)[1]
TAG_INSERT = TAG_INSERT.split("updateTagName:", 1)[0]
RELATION_SQL = (SQL_ROOT / "data/localMangaTags.sq").read_text(encoding="utf-8")
PARTS = re.split(r"(?m)^([a-zA-Z]+):\s*$", RELATION_SQL)
DDL = PARTS[0]
QUERIES = dict(zip(PARTS[1::2], PARTS[2::2]))
MIGRATION = (SQL_ROOT / "migrations/49.sqm").read_text(encoding="utf-8")

# Required columns from mangas.sq; progress belongs to the separate chapters table.
MANGAS = """CREATE TABLE mangas (
    _id INTEGER PRIMARY KEY, source INTEGER NOT NULL, url TEXT NOT NULL,
    title TEXT NOT NULL, status INTEGER NOT NULL, favorite INTEGER NOT NULL,
    initialized INTEGER NOT NULL, viewer INTEGER NOT NULL, chapter_flags INTEGER NOT NULL,
    cover_last_modified INTEGER NOT NULL, date_added INTEGER NOT NULL
);
CREATE TABLE chapters (
    _id INTEGER PRIMARY KEY, manga_id INTEGER REFERENCES mangas(_id) ON DELETE CASCADE,
    read INTEGER NOT NULL, last_page_read INTEGER NOT NULL
);"""


class LocalMangaTagsSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript(MANGAS + TAG_DDL + DDL)

    def ensure_manga(self, url="Folder", title="Title"):
        self.db.execute(QUERIES["ensureLocalManga"], {"source_id": 0, "url": url, "title": title})
        return self.db.execute(QUERIES["getLocalMangaId"], {"source_id": 0, "url": url}).fetchone()[0]

    def replace_tags(self, manga_id, names):
        with self.db:
            self.db.execute(QUERIES["clearMangaTags"], {"manga_id": manga_id})
            for name in names:
                key = name.strip().lower()
                self.db.execute(TAG_INSERT, {"name": name.strip(), "normalized_name": key})
                self.db.execute(QUERIES["insertMangaTag"], {"manga_id": manga_id, "normalized_name": key})

    def tags(self, manga_id):
        return self.db.execute(QUERIES["getTagsForManga"], {"manga_id": manga_id}).fetchall()

    def test_language_alias_merge_preserves_all_manga_links_and_progress(self):
        first = self.ensure_manga("First")
        second = self.ensure_manga("Second")
        self.replace_tags(first, ["Idioma: ingles", "English"])
        self.replace_tags(second, ["Idioma: ingles"])
        self.db.execute("INSERT INTO chapters VALUES(1, ?, 1, 12)", (first,))
        old = self.db.execute("SELECT id FROM local_tags WHERE normalized_name='idioma: ingles'").fetchone()[0]
        target = self.db.execute("SELECT id FROM local_tags WHERE normalized_name='english'").fetchone()[0]
        parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", TAG_SQL)
        tag_queries = dict(zip(parts[1::2], parts[2::2]))
        with self.db:
            self.db.execute(QUERIES["mergeTagLinks"], {"target_id": target, "old_id": old})
            self.db.execute(QUERIES["clearTagLinks"], {"tag_id": old})
            self.db.execute(tag_queries["deleteTag"], {"id": old})
            self.db.execute(tag_queries["updateTagName"], {"id": target, "name": "English", "normalized_name": "english"})
        self.assertEqual([(target, "English", "english")], self.tags(first))
        self.assertEqual(self.tags(first), self.tags(second))
        self.assertEqual([(1, first, 1, 12)], self.db.execute("SELECT * FROM chapters").fetchall())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_new_titles_are_registered_outside_library_without_duplicates(self):
        manga_id = self.ensure_manga()
        self.assertEqual(manga_id, self.ensure_manga())
        self.assertEqual([(0, 0, 0)], self.db.execute("SELECT source, favorite, initialized FROM mangas").fetchall())

    def test_existing_favorites_metadata_and_progress_are_preserved(self):
        manga_id = self.ensure_manga(title="Original title")
        self.db.execute("UPDATE mangas SET favorite=1, initialized=1, viewer=7, date_added=123 WHERE _id=?", (manga_id,))
        self.db.execute("INSERT INTO chapters VALUES(1, ?, 1, 25)", (manga_id,))
        original = self.db.execute("SELECT * FROM mangas").fetchall()
        self.ensure_manga(title="Different file title")
        self.replace_tags(manga_id, ["Adventure"])
        self.assertEqual(original, self.db.execute("SELECT * FROM mangas").fetchall())
        self.assertEqual([(1, manga_id, 1, 25)], self.db.execute("SELECT * FROM chapters").fetchall())

    def test_repeated_tags_are_unique_and_tag_ids_remain_stable(self):
        manga_id = self.ensure_manga()
        self.replace_tags(manga_id, ["Adventure", "ADVENTURE"])
        first = self.tags(manga_id)
        self.assertEqual(1, len(first))
        self.replace_tags(manga_id, ["Adventure"])
        self.assertEqual(first, self.tags(manga_id))

    def test_edit_replaces_only_the_target_titles_links_and_empty_list_clears(self):
        first = self.ensure_manga()
        second = self.ensure_manga("Other")
        self.replace_tags(first, ["Adventure", "Comedy"])
        self.replace_tags(second, ["Adventure"])
        self.replace_tags(first, ["Romance"])
        self.assertEqual(["Romance"], [tag[1] for tag in self.tags(first)])
        self.assertEqual(["Adventure"], [tag[1] for tag in self.tags(second)])
        self.replace_tags(first, [])
        self.assertEqual([], self.tags(first))
        self.assertEqual(3, self.db.execute("SELECT COUNT(*) FROM local_tags").fetchone()[0])

    def test_failed_transaction_restores_previous_links(self):
        manga_id = self.ensure_manga()
        self.replace_tags(manga_id, ["Adventure"])
        original = self.tags(manga_id)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute(QUERIES["clearMangaTags"], {"manga_id": manga_id})
                self.db.execute("INSERT INTO local_manga_tags VALUES(?, -1)", (manga_id,))
        self.assertEqual(original, self.tags(manga_id))

    def test_other_sources_do_not_collide_or_receive_local_links(self):
        other = self.ensure_manga()
        self.db.execute("UPDATE mangas SET source=123 WHERE _id=?", (other,))
        local = self.ensure_manga()
        self.assertNotEqual(other, local)
        self.replace_tags(other, ["Adventure"])
        self.assertEqual([], self.tags(other))

    def test_foreign_keys_cascade_in_both_directions(self):
        manga_id = self.ensure_manga()
        self.replace_tags(manga_id, ["Adventure", "Comedy"])
        tag_id = self.tags(manga_id)[0][0]
        self.db.execute("DELETE FROM local_tags WHERE id=?", (tag_id,))
        self.assertEqual(1, len(self.tags(manga_id)))
        self.db.execute("DELETE FROM mangas WHERE _id=?", (manga_id,))
        self.assertEqual([], self.db.execute("SELECT * FROM local_manga_tags").fetchall())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_migration_matches_fresh_schema_and_preserves_existing_rows(self):
        with sqlite3.connect(":memory:") as migrated:
            migrated.execute("PRAGMA foreign_keys = ON")
            migrated.executescript(MANGAS + TAG_DDL)
            migrated.execute(QUERIES["ensureLocalManga"], {"source_id": 0, "url": "Folder", "title": "Preserved"})
            before = migrated.execute("SELECT * FROM mangas").fetchall()
            migrated.executescript(MIGRATION)
            self.assertEqual(before, migrated.execute("SELECT * FROM mangas").fetchall())
            for pragma in ["table_info(local_manga_tags)", "foreign_key_list(local_manga_tags)"]:
                self.assertEqual(self.db.execute("PRAGMA " + pragma).fetchall(), migrated.execute("PRAGMA " + pragma).fetchall())
            self.assertEqual(self.db.execute("SELECT sql FROM sqlite_master WHERE name='local_manga_tags_tag_index'").fetchone(), migrated.execute("SELECT sql FROM sqlite_master WHERE name='local_manga_tags_tag_index'").fetchone())


if __name__ == "__main__":
    unittest.main()
