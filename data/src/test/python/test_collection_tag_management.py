"""Execute catalog isolation and legacy cleanup using production SQL."""
import re
import unittest
import test_file_source_isolation as fixtures

parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", (fixtures.SQL_ROOT / "data/localTagCollections.sq").read_text(encoding="utf-8"))
DDL = parts[0]
QUERIES = dict(zip(parts[1::2], parts[2::2]))
NSFW = 2000000123456
OTHER = 2000000654321

class CatalogManagementTest(unittest.TestCase):
    setUp = fixtures.FileSourceIsolationTest.setUp
    register = fixtures.FileSourceIsolationTest.register
    replace = fixtures.FileSourceIsolationTest.replace
    catalog = fixtures.FileSourceIsolationTest.catalog
    manga_tags = fixtures.FileSourceIsolationTest.manga_tags

    def setup_catalogs(self):
        self.db.executescript(DDL)
        self.local = self.register(0, ["Local", "Shared"], "Old")
        self.private = self.register(fixtures.PRIVATE, ["Private"], "Private")
        self.nsfw = self.register(NSFW, ["Existing"], "NSFW")
        self.other = self.register(OTHER, ["Other"], "Other")
        self.db.commit()

    def cleanup(self):
        self.db.execute(QUERIES["ensureCollection"], dict(source_id=NSFW))
        self.db.execute(QUERIES["copyLegacyCatalog"], dict(target_source=NSFW))
        self.db.execute(QUERIES["clearLegacyLinks"])
        self.db.execute(QUERIES["clearLegacyCatalog"])

    def test_cleanup_preserves_dictionary_and_other_collection_links(self):
        self.setup_catalogs()
        rows = self.db.execute("SELECT * FROM mangas").fetchall()
        dictionary = self.db.execute("SELECT * FROM local_tags").fetchall()
        with self.db:
            self.cleanup()
        self.assertEqual([], self.manga_tags(self.local))
        self.assertEqual([], self.manga_tags(self.private))
        self.assertEqual([], self.catalog(0))
        self.assertEqual([], self.catalog(fixtures.PRIVATE))
        self.assertEqual(["Existing", "Local", "Private", "Shared"], [row[1] for row in self.catalog(NSFW)])
        self.assertEqual(["Existing"], [row[1] for row in self.manga_tags(self.nsfw)])
        self.assertEqual(["Other"], [row[1] for row in self.catalog(OTHER)])
        self.assertEqual(["Other"], [row[1] for row in self.manga_tags(self.other)])
        self.assertEqual(rows, self.db.execute("SELECT * FROM mangas").fetchall())
        self.assertEqual(dictionary, self.db.execute("SELECT * FROM local_tags").fetchall())

    def test_failure_rolls_back_catalog_copy_and_deleted_links(self):
        self.setup_catalogs()
        before = self.db.execute("SELECT * FROM local_manga_tags").fetchall()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.cleanup()
                raise RuntimeError("Simulated failure")
        self.assertEqual(before, self.db.execute("SELECT * FROM local_manga_tags").fetchall())
        self.assertEqual(["Existing"], [row[1] for row in self.catalog(NSFW)])

    def test_new_collection_starts_empty_and_export_is_source_scoped(self):
        self.setup_catalogs()
        self.db.execute(QUERIES["ensureCollection"], dict(source_id=OTHER + 1))
        self.assertEqual([], self.catalog(OTHER + 1))
        self.assertEqual([], self.db.execute(QUERIES["exportLinks"], dict(source_id=OTHER + 1)).fetchall())
        links = self.db.execute(QUERIES["exportLinks"], dict(source_id=OTHER)).fetchall()
        self.assertEqual(1, len(links))
        self.assertEqual(self.other, links[0][0])
        self.assertEqual("Other", links[0][-1])

    def test_migration_51_preserves_existing_data(self):
        self.register(0, ["Old"])
        before = self.db.execute("SELECT * FROM local_tags").fetchall()
        self.db.executescript((fixtures.SQL_ROOT / "migrations/51.sqm").read_text(encoding="utf-8"))
        self.assertEqual(before, self.db.execute("SELECT * FROM local_tags").fetchall())
        self.assertEqual([(0,)], self.db.execute("SELECT * FROM local_tag_collections").fetchall())
