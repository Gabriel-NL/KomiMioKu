"""Exercise production relocation SQL with foreign keys, progress/history, tags and categories."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "main/sqldelight/tachiyomi/data"
parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", (ROOT / "collectionFileEdits.sq").read_text(encoding="utf-8"))
Q = dict(zip(parts[1::2], parts[2::2]))

class CollectionFileEditsTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
        CREATE TABLE mangas(_id INTEGER PRIMARY KEY, source INTEGER, url TEXT, title TEXT, thumbnail_url TEXT, initialized INTEGER, favorite INTEGER, notes TEXT, genre TEXT, cover_last_modified INTEGER);
        CREATE TABLE chapters(_id INTEGER PRIMARY KEY, manga_id INTEGER REFERENCES mangas(_id) ON DELETE CASCADE, url TEXT, name TEXT, read INTEGER, bookmark INTEGER, last_page_read INTEGER, chapter_number REAL, source_order INTEGER, date_fetch INTEGER, date_upload INTEGER);
        CREATE TABLE history(chapter_id INTEGER REFERENCES chapters(_id) ON DELETE CASCADE,last_read INTEGER,time_read INTEGER);
        CREATE TABLE local_manga_tags(manga_id INTEGER REFERENCES mangas(_id) ON DELETE CASCADE,tag_id INTEGER,PRIMARY KEY(manga_id,tag_id));
        CREATE TABLE mangas_categories(manga_id INTEGER REFERENCES mangas(_id) ON DELETE CASCADE,category_id INTEGER);
        INSERT INTO mangas VALUES(1,2000000000001,'A','A','cover',1,0,'notes A','a',0),(2,2000000000001,'B','B',NULL,1,1,'notes B','b',0),(3,2000000000002,'Other','Other',NULL,1,0,'','',0);
        INSERT INTO chapters VALUES(11,1,'A/1.cbz','One',1,1,12,1,0,10,20),(22,2,'B/1.cbz','One',0,1,4,1,0,10,20),(33,3,'Other/1.cbz','One',0,0,0,1,0,10,20);
        INSERT INTO history VALUES(11,100,900),(22,200,800);
        INSERT INTO local_manga_tags VALUES(1,1),(2,1),(2,2),(3,3);
        INSERT INTO mangas_categories VALUES(1,10),(2,10),(2,20),(3,30);
        """)
        self.db.commit()

    def apply_merge(self):
        self.db.execute(Q['relocateManga'],dict(url='Joined',title='Joined',favorite=1,notes='notes A\nnotes B',genre='a,b',modified=9,id=1,source=2000000000001))
        for ident,url,number in [(11,'Joined/000001.cbz',1),(22,'Joined/000002.cbz',2)]:
            self.db.execute(Q['relocateChapter'],dict(manga=1,url=url,name='One',number=number,source_order=2-number,id=ident))
        self.db.execute(Q['copyTags'],dict(target=1,old=2))
        self.db.execute(Q['copyCategories'],dict(target=1,old=2))
        self.db.execute(Q['removeMergedManga'],dict(id=2,source=2000000000001))

    def test_merge_keeps_chapter_ids_history_read_progress_and_unrelated_collection(self):
        with self.db: self.apply_merge()
        self.assertEqual([(11,1,1,1,12),(22,1,0,1,4),(33,3,0,0,0)],self.db.execute('SELECT _id,manga_id,read,bookmark,last_page_read FROM chapters ORDER BY _id').fetchall())
        self.assertEqual([(11,100,900),(22,200,800)],self.db.execute('SELECT * FROM history').fetchall())
        self.assertEqual([(1,1),(1,2),(3,3)],self.db.execute('SELECT * FROM local_manga_tags ORDER BY manga_id,tag_id').fetchall())
        self.assertEqual([(1,10),(1,20),(3,30)],self.db.execute('SELECT * FROM mangas_categories ORDER BY manga_id,category_id').fetchall())
        self.assertEqual(('Other',),self.db.execute('SELECT url FROM mangas WHERE _id=3').fetchone())

    def test_failed_transaction_restores_all_relations_and_reading_state(self):
        before={table:self.db.execute('SELECT * FROM '+table).fetchall() for table in ['mangas','chapters','history','local_manga_tags','mangas_categories']}
        with self.assertRaises(RuntimeError):
            with self.db:
                self.apply_merge()
                raise RuntimeError('interrupted')
        self.assertEqual(before,{table:self.db.execute('SELECT * FROM '+table).fetchall() for table in before})

    def test_rename_preserves_chapter_state_and_manga_identity(self):
        self.db.execute(Q['relocateChapter'],dict(manga=1,url='Renamed/New.cbz',name='New',number=1,source_order=0,id=11))
        self.assertEqual((11,1,'Renamed/New.cbz','New',1,1,12),self.db.execute('SELECT _id,manga_id,url,name,read,bookmark,last_page_read FROM chapters WHERE _id=11').fetchone())
        self.assertEqual(2,self.db.execute('SELECT count(*) FROM history').fetchone()[0])
