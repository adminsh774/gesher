"""Metadata/transport controls; synthetic payloads are not runtime evidence."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import producer_context as proof


class RetainedProducer(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.consumer = dict(source='1' * 40, supplier_head='2' * 40, repository='owner/repo',
                             repository_id='7', head_repository_id='8', run='9',
                             attempt='2', event='pull_request')
        self.original = dict(self.consumer, attempt='1')
        self.run = dict(id=9, run_attempt=1, head_sha='2' * 40, name='ci', event='pull_request',
                        repository=dict(id=7, full_name='owner/repo'), head_repository=dict(id=8))
        self.old_job = dict(id=10, name='build', run_id=9, run_attempt=1, head_sha='2' * 40,
                            status='completed', conclusion='success',
                            started_at='2026-11-20T12:01:13Z', completed_at='2026-11-20T12:07:27Z')
        self.new_job = dict(self.old_job, id=20, run_attempt=2)
        self.item = dict(id=30, name='windows-x64', digest='sha256:' + '3' * 64,
                         size_in_bytes=100, expired=False, created_at='2026-11-20T12:06:40Z',
                         workflow_run=dict(id=9, head_sha='2' * 40, repository_id=7, head_repository_id=8))
        self.old_jobs, self.new_jobs = [self.old_job], [self.new_job]
        self.items = [self.item]
        self.run_reads, self.downloads = [], []
        self.download_mutation = None
        self.archive_mutation = None
        self.role = 'production'
        self.write_role(self.role)
        env = patch.dict(os.environ, GITHUB_ACTIONS='true', GITHUB_EVENT_NAME='pull_request',
                         GITHUB_WORKFLOW='ci', GITHUB_RUN_ATTEMPT='2')
        env.start(); self.addCleanup(env.stop)
        for name, callback in (
                ('get_json', self.get_json), ('read_jobs', self.read_jobs),
                ('read_artifacts', lambda *a, **k: copy.deepcopy(self.items)),
                ('check_run', self.check_run), ('check_checkout', lambda context: None),
                ('download', self.download)):
            mocked = patch.object(proof.receipts, name, side_effect=callback)
            mocked.start(); self.addCleanup(mocked.stop)

    def write_role(self, role):
        member, artifact, producer, nested = proof.ROLES[role]
        self.role = role
        self.item['name'] = artifact
        self.old_job['name'] = self.new_job['name'] = producer
        self.path = self.root / member
        header = dict(self.original)
        payload = header if nested is None else {nested: header}
        payload.update(role=role, fixture_metadata_only=True)
        self.raw = (json.dumps(payload, sort_keys=True) + '\n').encode()
        self.path.write_bytes(self.raw)

    def get_json(self, endpoint):
        self.assertEqual(endpoint, 'repos/owner/repo/actions/runs/9/attempts/1')
        return copy.deepcopy(self.run)

    def read_jobs(self, context):
        rows = self.old_jobs if context['attempt'] == '1' else self.new_jobs
        return dict(total_count=len(rows), jobs=copy.deepcopy(rows))

    def check_run(self, context):
        self.run_reads.append(copy.deepcopy(context))
        self.assertEqual(context['attempt'], '2')
        self.assertEqual(os.environ['GITHUB_RUN_ATTEMPT'], '2')

    def download(self, item, destination):
        self.downloads.append(copy.deepcopy(item))
        self.assertEqual(item, self.item)
        destination.mkdir()
        archived = destination / proof.ROLES[self.role][0]
        archived.write_bytes(self.raw)
        if self.archive_mutation is not None:
            self.archive_mutation(archived)
        if self.download_mutation is not None:
            self.download_mutation()

    def verify(self):
        return proof.authenticate(self.path, self.consumer, self.role)

    def rejected(self):
        with self.assertRaises((RuntimeError, KeyError, ValueError)):
            self.verify()

    def test_original_execution_and_current_consumer_are_distinct(self):
        self.assertEqual(self.verify(), self.original)
        self.assertEqual(os.environ['GITHUB_RUN_ATTEMPT'], '2')
        self.assertEqual(self.consumer['attempt'], '2')
        self.assertEqual(len(self.downloads), 1)
        self.assertEqual(len(self.run_reads), 3)

    def test_all_current_role_archives_have_explicit_policy(self):
        for role in proof.ROLES:
            with self.subTest(role=role):
                self.write_role(role)
                self.assertEqual(self.verify(), self.original)

    def test_same_attempt_needs_no_historical_artifact_download(self):
        self.consumer['attempt'] = '1'
        self.assertEqual(self.verify(), self.original)
        self.assertEqual(self.downloads, [])
        self.assertEqual(self.run_reads, [])

    def test_every_nonattempt_identity_field_is_still_exact(self):
        for key in proof.CONTEXT_KEYS:
            if key == 'attempt':
                continue
            with self.subTest(key=key):
                original = self.consumer[key]
                self.consumer[key] = 'wrong'
                self.rejected()
                self.consumer[key] = original
        self.assertEqual(self.downloads, [])

    def test_original_attempt_is_not_arbitrary_or_future(self):
        for value in ('0', '-1', '03', '3', 1, None):
            with self.subTest(value=value):
                payload = json.loads(self.raw); payload['attempt'] = value
                self.path.write_text(json.dumps(payload))
                self.rejected()

    def test_unknown_and_mislabelled_role_fail(self):
        self.role = 'not-a-role'; self.rejected()
        self.role = 'query-hook'; self.rejected()

    def test_original_run_identity_and_event_are_required(self):
        for key, value in (('id', 8), ('run_attempt', 2), ('head_sha', '4' * 40),
                           ('name', 'other'), ('event', 'push'),
                           ('repository', dict(id=8, full_name='owner/repo')),
                           ('repository', dict(id=7, full_name='other/repo')),
                           ('head_repository', dict(id=9))):
            with self.subTest(key=key, value=value):
                saved = self.run[key]; self.run[key] = value
                self.rejected(); self.run[key] = saved

    def test_both_job_records_must_be_successful_exact_executions(self):
        for job in (self.old_job, self.new_job):
            for key, value in (('status', 'in_progress'), ('conclusion', 'failure'),
                               ('head_sha', '4' * 40), ('run_id', 8), ('run_attempt', 3),
                               ('name', 'other')):
                with self.subTest(attempt=job['run_attempt'], key=key):
                    saved = job[key]; job[key] = value
                    self.rejected(); job[key] = saved

    def test_missing_or_duplicate_producer_is_rejected(self):
        for name in ('old_jobs', 'new_jobs'):
            saved = getattr(self, name)
            for rows in ([], [*saved, dict(saved[0], id=100)]):
                with self.subTest(name=name, count=len(rows)):
                    setattr(self, name, rows); self.rejected()
            setattr(self, name, saved)

    def test_reexecuted_current_producer_cannot_consume_old_success(self):
        self.new_job.update(started_at='2026-11-20T13:01:13Z', completed_at='2026-11-20T13:07:27Z')
        self.rejected()

    def test_all_archive_identity_expiry_size_and_digest_guards_remain(self):
        for key, value in (('digest', None), ('digest', 'sha256:' + 'x' * 64),
                           ('expired', True), ('size_in_bytes', 0),
                           ('created_at', '2026-11-20T12:00:00Z'),
                           ('created_at', '2026-11-20T12:08:00Z'),
                           ('workflow_run', dict(id=10, head_sha='2' * 40, repository_id=7, head_repository_id=8)),
                           ('workflow_run', dict(id=9, head_sha='4' * 40, repository_id=7, head_repository_id=8)),
                           ('workflow_run', dict(id=9, head_sha='2' * 40, repository_id=8, head_repository_id=8)),
                           ('workflow_run', dict(id=9, head_sha='2' * 40, repository_id=7, head_repository_id=9))):
            with self.subTest(key=key, value=value):
                saved = self.item[key]; self.item[key] = value
                self.rejected(); self.item[key] = saved

    def test_later_same_name_output_and_duplicate_output_are_rejected(self):
        self.items.append(dict(self.item, id=31, created_at='2026-11-20T13:06:40Z'))
        self.rejected()
        self.items[-1]['created_at'] = self.item['created_at']; self.rejected()

    def test_actual_digest_checked_archive_role_bytes_must_match(self):
        self.archive_mutation = lambda path: path.write_bytes(b'forged role')
        self.rejected()
        self.archive_mutation = lambda path: path.unlink()
        self.rejected()

    def test_original_or_reused_record_cannot_change_during_download(self):
        for job in (self.old_job, self.new_job):
            with self.subTest(attempt=job['run_attempt']):
                saved = job['id']
                self.download_mutation = lambda: job.update(id=101)
                self.rejected(); job['id'] = saved

    def test_artifact_digest_cannot_change_during_download(self):
        self.download_mutation = lambda: self.item.update(digest='sha256:' + '4' * 64)
        self.rejected()

    def test_new_current_attempt_during_read_is_rejected(self):
        with patch.object(proof.receipts, 'check_run', side_effect=RuntimeError('attempt advanced')):
            self.rejected()

    def test_local_role_cannot_change_after_its_archive_was_verified(self):
        self.download_mutation = lambda: self.path.write_bytes(b'changed')
        self.rejected()


if __name__ == '__main__':
    unittest.main()
