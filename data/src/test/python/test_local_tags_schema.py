"""Exercise the actual Local tag schema and migration with Python's built-in SQLite."""
from pathlib import Path
import sqlite3
import unittest


ROOT = Path(__file__).resolve().parents[4]
SCHEMA = ROOT / "data/src/main/sqldelight/tachiyomi/data/localTags.sq"
MIGRATION = ROOT / "data/src/main/sqldelight/tachiyomi/migrations/48.sqm"
DDL, QUERIES = SCHEMA.read_text(encoding="utf-8").split("getAllTags:", 1)
SELECT, INSERT = QUERIES.split("insertTag:", 1)
INSERT = INSERT.split("updateTagName:", 1)[0]


class LocalTagsSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)

    def test_migration_preserves_existing_data(self):
        self.db.executescript("CREATE TABLE existing_manga(id INTEGER PRIMARY KEY, title TEXT);"
                              "INSERT INTO existing_manga VALUES(1, 'Preserved title');")
        self.db.executescript(MIGRATION.read_text(encoding="utf-8"))
        self.assertEqual([(1, "Preserved title")], self.db.execute("SELECT * FROM existing_manga").fetchall())
        self.assertEqual([], self.db.execute(SELECT).fetchall())
        self.assertEqual("ok", self.db.execute("PRAGMA integrity_check").fetchone()[0])

    def test_migration_and_fresh_schema_create_the_same_table(self):
        self.db.executescript(DDL)
        with sqlite3.connect(":memory:") as migrated:
            migrated.executescript(MIGRATION.read_text(encoding="utf-8"))
            self.assertEqual(self.db.execute("PRAGMA table_info(local_tags)").fetchall(),
                             migrated.execute("PRAGMA table_info(local_tags)").fetchall())
            self.assertEqual(self.db.execute("SELECT sql FROM sqlite_master WHERE name='local_tags'").fetchone(),
                             migrated.execute("SELECT sql FROM sqlite_master WHERE name='local_tags'").fetchone())

    def test_duplicate_normalized_names_keep_the_original_id(self):
        self.db.executescript(DDL)
        self.db.execute(INSERT, {"name": "Adventure", "normalized_name": "adventure"})
        original = self.db.execute(SELECT).fetchone()
        self.db.execute(INSERT, {"name": "ADVENTURE", "normalized_name": "adventure"})
        self.assertEqual([original], self.db.execute(SELECT).fetchall())
        self.assertGreater(original[0], 0)

    def test_different_tags_receive_distinct_ids_and_sort_by_normalized_name(self):
        self.db.executescript(DDL)
        for name in ["Romance", "Adventure", "Comedy"]:
            self.db.execute(INSERT, {"name": name, "normalized_name": name.lower()})
        rows = self.db.execute(SELECT).fetchall()
        self.assertEqual(["Adventure", "Comedy", "Romance"], [row[1] for row in rows])
        self.assertEqual(3, len({row[0] for row in rows}))


if __name__ == "__main__":
    unittest.main()
