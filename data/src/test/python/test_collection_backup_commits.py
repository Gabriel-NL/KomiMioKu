"""The recovery marker and collection-local sync reset use production SQL."""
import sqlite3
import re
import unittest
from test_local_manga_tags_schema import SQL_ROOT, MANGAS, QUERIES as MANGA

parts = re.split(r"(?m)^([a-zA-Z]+):\s*$", (SQL_ROOT / "data/collectionBackupCommits.sq").read_text(encoding="utf-8"))
DDL = parts[0]
Q = dict(zip(parts[1::2], parts[2::2]))

class CollectionBackupCommitTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript(MANGAS + DDL)
        self.db.execute("ALTER TABLE chapters ADD COLUMN is_syncing INTEGER DEFAULT 0")

    def test_commit_marker_rolls_back_with_failed_user_data_transaction(self):
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute(Q["markCommitted"], dict(operation_id="failed", source_id=10))
                raise RuntimeError("Interrupted")
        self.assertEqual(0, self.db.execute(Q["isCommitted"], dict(operation_id="failed")).fetchone()[0])
        with self.db:
            self.db.execute(Q["markCommitted"], dict(operation_id="done", source_id=10))
        self.assertEqual(1, self.db.execute(Q["isCommitted"], dict(operation_id="done")).fetchone()[0])

    def test_only_selected_collection_chapter_flags_are_reset(self):
        for source in [10,20]:
            self.db.execute(MANGA["ensureLocalManga"], dict(source_id=source,url="Book",title="Book"))
            manga = self.db.execute(MANGA["getLocalMangaId"], dict(source_id=source,url="Book")).fetchone()[0]
            self.db.execute("INSERT INTO chapters VALUES(?,?,?,?,?)", (source,manga,1,8,1))
        self.db.execute(Q["resetRestoredChapterSync"],dict(source_id=10))
        self.assertEqual([(10,0),(20,1)],self.db.execute("SELECT _id,is_syncing FROM chapters ORDER BY _id").fetchall())

    def test_migration_is_additive(self):
        self.db.executescript("DROP TABLE collection_backup_commits;")
        self.db.execute(MANGA["ensureLocalManga"],dict(source_id=10,url="Book",title="Book"))
        before = self.db.execute("SELECT * FROM mangas").fetchall()
        self.db.executescript((SQL_ROOT / "migrations/53.sqm").read_text(encoding="utf-8"))
        self.assertEqual(before,self.db.execute("SELECT * FROM mangas").fetchall())
