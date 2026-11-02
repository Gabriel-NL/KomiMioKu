import re
import unittest
from test_collection_file_edits import ROOT
import test_collection_file_edits as fixtures

class MangaManagerTest(unittest.TestCase):
    def setUp(self):
        fixtures.CollectionFileEditsTest.setUp(self)
        self.before = self.db.execute('SELECT * FROM chapters').fetchall()
        self.db.executescript((ROOT.parent / 'migrations/56.sqm').read_text(encoding='utf-8'))
        parts = re.split(r'(?m)^([a-zA-Z]+):\s*$', (ROOT / 'localManagerOperations.sq').read_text(encoding='utf-8'))
        self.q = dict(zip(parts[1::2], parts[2::2]))
    def test_migration_preserves_reading_data(self):
        self.assertEqual(self.before, self.db.execute('SELECT * FROM chapters').fetchall())
    def test_pending_journal_is_scoped_and_persists_until_removed(self):
        self.db.execute(self.q['put'], dict(operation='remove-a', source=2000000000001, root='root', payload='{"A":"hidden"}'))
        self.db.commit()
        self.assertEqual(1, len(self.db.execute(self.q['getPending'], dict(source=2000000000001)).fetchall()))
        self.assertEqual([], self.db.execute(self.q['getPending'], dict(source=2000000000002)).fetchall())
        self.db.execute(self.q['remove'], dict(operation='remove-a'))
        self.assertEqual([], self.db.execute(self.q['getPending'], dict(source=2000000000001)).fetchall())
    def test_failed_removal_keeps_journal_and_manga_records(self):
        self.db.execute(self.q['put'], dict(operation='remove-a', source=2000000000001, root='root', payload='{}'))
        self.db.commit()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute('DELETE FROM mangas WHERE _id=1')
                raise RuntimeError('interrupted')
        self.assertEqual(1, self.db.execute('SELECT count(*) FROM mangas WHERE _id=1').fetchone()[0])
        self.assertEqual(1, len(self.db.execute(self.q['getPending'], dict(source=2000000000001)).fetchall()))

    def test_renaming_scoped_catalog_does_not_remove_other_collection_tag(self):
        self.db.executescript("""
        CREATE TABLE local_tags(id INTEGER PRIMARY KEY, name TEXT, normalized_name TEXT);
        CREATE TABLE local_source_tags(source_id INTEGER, tag_id INTEGER, PRIMARY KEY(source_id,tag_id));
        INSERT INTO local_tags VALUES(1,'Old','old'),(2,'New','new');
        INSERT INTO local_source_tags VALUES(2000000000001,1),(2000000000002,1),(2000000000001,2);
        """)
        parts = re.split(r'(?m)^([a-zA-Z]+):\s*$', (ROOT/'localSourceTags.sq').read_text(encoding='utf-8'))
        queries = dict(zip(parts[1::2],parts[2::2]))
        self.db.execute(queries['removeSourceTag'], dict(source=2000000000001, name='old'))
        self.assertEqual([(2000000000001,2),(2000000000002,1)],self.db.execute('SELECT * FROM local_source_tags ORDER BY source_id').fetchall())
