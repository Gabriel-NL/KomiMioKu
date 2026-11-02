import re
import unittest
from test_collection_file_edits import ROOT
import test_collection_file_edits as fixtures
parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", (ROOT / "localExportReview.sq").read_text(encoding="utf-8"))
Q = dict(zip(parts[1::2],parts[2::2]))
class ExportReviewTest(unittest.TestCase):
    def setUp(self):
        fixtures.CollectionFileEditsTest.setUp(self)
        self.before = self.db.execute('SELECT * FROM chapters').fetchall()
        self.db.executescript((ROOT.parent/'migrations/55.sqm').read_text(encoding='utf-8'))
    def test_existing_manga_is_unapproved_by_default_and_progress_is_unchanged(self):
        self.assertEqual([('A',0),('B',0)],self.db.execute(Q['getApprovals'],dict(source=2000000000001)).fetchall())
        self.assertEqual(self.before,self.db.execute('SELECT * FROM chapters').fetchall())
    def test_approval_follows_id_across_rename_and_is_scoped_to_collection(self):
        self.db.execute(Q['approve'],dict(id=1))
        self.db.execute("UPDATE mangas SET url='New' WHERE _id=1")
        self.assertEqual([('B',0),('New',1)],self.db.execute(Q['getApprovals'],dict(source=2000000000001)).fetchall())
        self.assertEqual([('Other',0)],self.db.execute(Q['getApprovals'],dict(source=2000000000002)).fetchall())
        self.db.execute(Q['revoke'],dict(id=1))
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM local_export_approvals').fetchone()[0])
    def test_failed_batch_does_not_partially_approve_manga(self):
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute(Q['approve'],dict(id=1))
                raise RuntimeError('invalid target')
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM local_export_approvals').fetchone()[0])
