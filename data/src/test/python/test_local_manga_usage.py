import re
import unittest
from test_collection_file_edits import ROOT
import test_collection_file_edits as fixtures

class LocalMangaUsageTest(unittest.TestCase):
    def setUp(self):
        fixtures.CollectionFileEditsTest.setUp(self)
        self.chapters = self.db.execute('SELECT * FROM chapters').fetchall()
        self.history = self.db.execute('SELECT * FROM history').fetchall()
        self.db.executescript((ROOT.parent/'migrations/57.sqm').read_text(encoding='utf-8'))
        parts = re.split(r'(?m)^([a-zA-Z]+):\s*$', (ROOT/'localMangaUsage.sq').read_text(encoding='utf-8'))
        self.q = dict(zip(parts[1::2],parts[2::2]))
    def test_migration_starts_empty_without_rewriting_progress_or_history(self):
        self.assertEqual([],self.db.execute(self.q['getCounts'],dict(source=2000000000001)).fetchall())
        self.assertEqual(self.chapters,self.db.execute('SELECT * FROM chapters').fetchall())
        self.assertEqual(self.history,self.db.execute('SELECT * FROM history').fetchall())
    def test_counts_are_per_manga_and_collection_and_follow_renames(self):
        for ident in (1,1,2,3): self.db.execute(self.q['increment'],dict(manga_id=ident))
        self.db.execute("UPDATE mangas SET url='Renamed' WHERE _id=1")
        self.assertEqual([('B',1),('Renamed',2)],self.db.execute(self.q['getCounts'],dict(source=2000000000001)).fetchall())
        self.assertEqual([('Other',1)],self.db.execute(self.q['getCounts'],dict(source=2000000000002)).fetchall())
        self.assertEqual(self.chapters,self.db.execute('SELECT * FROM chapters').fetchall())
    def test_network_records_are_not_counted(self):
        self.db.execute("INSERT INTO mangas(_id,source,url,title) VALUES(4,123,'Network','Network')")
        self.db.execute(self.q['increment'],dict(manga_id=4))
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM local_manga_usage').fetchone()[0])
    def test_restoration_remaps_collection_and_never_downgrades_newer_usage(self):
        for count in (2,7,3,7): self.db.execute(self.q['restore'],dict(open_count=count,source=2000000000002,url='Other'))
        self.assertEqual([(3,7)],self.db.execute('SELECT * FROM local_manga_usage').fetchall())
    def test_overflow_saturates_and_deletion_cascades(self):
        self.db.execute(self.q['setCount'],dict(manga_id=1,open_count=9223372036854775807))
        self.db.execute(self.q['increment'],dict(manga_id=1))
        self.assertEqual(9223372036854775807,self.db.execute('SELECT open_count FROM local_manga_usage').fetchone()[0])
        self.db.execute('DELETE FROM mangas WHERE _id=1')
        self.assertEqual([],self.db.execute('SELECT * FROM local_manga_usage').fetchall())
