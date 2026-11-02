"""Production migration/queries: metadata is atomic, source-scoped and preserves reading state."""
import re
import unittest
from test_collection_file_edits import ROOT
import test_collection_file_edits as fixtures
parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", (ROOT / "localMetadata.sq").read_text(encoding="utf-8"))
DDL = parts[0]
Q = dict(zip(parts[1::2],parts[2::2]))

class LocalDatabaseMetadataTest(unittest.TestCase):
    def setUp(self):
        fixtures.CollectionFileEditsTest.setUp(self)
        for column in ['author TEXT','artist TEXT','description TEXT','status INTEGER DEFAULT 0']:
            self.db.execute('ALTER TABLE mangas ADD COLUMN '+column)
        self.db.commit()

    def migrate(self):
        self.db.executescript((ROOT.parent / 'migrations/54.sqm').read_text(encoding='utf-8'))

    def save(self):
        self.db.execute(Q['saveFields'], dict(title='Edited', author=None,artist=None,description=None,genre='[]',status=0,thumbnail=None,manga_id=1))

    def test_migration_preserves_existing_mangas_chapters_and_history(self):
        before={t:self.db.execute('SELECT * FROM '+t).fetchall() for t in ['mangas','chapters','history','local_manga_tags']}
        self.migrate()
        self.assertEqual(before,{t:self.db.execute('SELECT * FROM '+t).fetchall() for t in before})
        self.assertEqual(0,self.db.execute(Q['isImported'],dict(manga_id=1)).fetchone()[0])

    def test_fields_can_be_cleared_without_changing_progress_or_other_collections(self):
        self.migrate()
        before=self.db.execute('SELECT * FROM chapters').fetchall()
        self.save()
        self.assertEqual(('Edited',None,None,'[]'), self.db.execute('SELECT title,author,description,genre FROM mangas WHERE _id=1').fetchone())
        self.assertEqual(before,self.db.execute('SELECT * FROM chapters').fetchall())
        self.assertEqual(('Other',),self.db.execute('SELECT title FROM mangas WHERE _id=3').fetchone())

    def test_failed_import_rolls_back_fields_document_and_completion_marker(self):
        self.migrate()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.save()
                self.db.execute(Q['putDocument'],dict(source=2000000000001,url='A',path='ComicInfo.xml',format='utf8',content='<ComicInfo><Unknown>Keep</Unknown></ComicInfo>'))
                self.db.execute(Q['markImported'],dict(manga_id=1))
                raise RuntimeError('interrupted')
        self.assertEqual(('A',),self.db.execute('SELECT title FROM mangas WHERE _id=1').fetchone())
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM local_metadata_documents').fetchone()[0])
        self.assertEqual(0,self.db.execute(Q['isImported'],dict(manga_id=1)).fetchone()[0])

    def test_same_document_name_is_isolated_by_collection(self):
        self.migrate()
        for source,value in [(2000000000001,'first'),(2000000000002,'second')]:
            self.db.execute(Q['putDocument'],dict(source=source,url='Same',path='ComicInfo.xml',format='utf8',content=value))
        self.assertEqual([('Same','ComicInfo.xml','utf8','first')],self.db.execute(Q['getDocuments'],dict(source=2000000000001)).fetchall())

    def test_journal_updates_keep_original_snapshot_and_cleanup_has_no_history(self):
        self.migrate()
        self.db.execute(Q['putOperation'],dict(operation='op',source=1,payload='before move',before_data='original records'))
        self.db.execute(Q['putOperation'],dict(operation='op',source=1,payload='after move',before_data=None))
        self.assertEqual(('after move','original records'),self.db.execute('SELECT payload,before_data FROM local_file_operations').fetchone())
        self.db.execute(Q['deleteOperation'],dict(operation='op'))
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM local_file_operations').fetchone()[0])
