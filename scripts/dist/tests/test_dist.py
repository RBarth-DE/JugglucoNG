import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

BASE = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, BASE / f'{name}.py')
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


dist = module('dist')
request = module('request')


class Requests(unittest.TestCase):
    def test_only_exact_allowlisted_commands(self):
        for target in dist.TARGETS:
            event = {'comment': {'user': {'id': 65550090}, 'body': '/build-dist ' + target}}
            self.assertEqual(request.requested_target('issue_comment', event), target)
        for body in ['/build-dist all; echo leak', '/build-dist $(id)', '/build-dist main', '/build-dist all\nmalicious', 'quoted /build-dist all']:
            self.assertIsNone(request.requested_target('issue_comment', {'comment': {'user': {'id': 3178592}, 'body': body}}))
        self.assertIsNone(request.requested_target('issue_comment', {'comment': {'user': {'id': 1, 'login': 'JetFoxy'}, 'body': '/build-dist all'}}))
        self.assertIsNone(request.requested_target('pull_request', {}))
        self.assertIsNone(request.requested_target('workflow_dispatch', {'inputs': {'target': 'untrusted'}}))


class InputRestore(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.payload = b'known native input'
        self.spec = {'path': 'Common/src/main/jniLibs/arm64-v8a/libexample.so', 'size': len(self.payload), 'sha256': dist.digest(self.payload)}
        self.apk = self.root / 'baseline.apk'
        with zipfile.ZipFile(self.apk, 'w') as z:
            z.writestr('lib/arm64-v8a/libexample.so', self.payload)
            z.writestr('../../must-not-extract', b'not allowed')
        self.data = {'source': {'sha256': dist.digest(self.apk.read_bytes())}, 'files': [self.spec]}
        for p in [patch.object(dist, 'ROOT', self.root), patch.object(dist, 'inventory', return_value=self.data), patch.object(dist, 'abis', return_value=['arm64-v8a'])]:
            p.start()
            self.addCleanup(p.stop)

    def test_restore_allowlist_and_idempotence(self):
        dist.restore(self.apk)
        dist.restore(self.apk)
        self.assertEqual((self.root / self.spec['path']).read_bytes(), self.payload)
        self.assertEqual(list(self.root.rglob('*.so')), [self.root / self.spec['path']])

    def test_source_tamper_fails_before_writes(self):
        self.apk.write_bytes(self.apk.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'source APK checksum'):
            dist.restore(self.apk)
        self.assertFalse((self.root / 'Common').exists())

    def test_input_tamper_fails_before_writes(self):
        self.spec['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Input checksum'):
            dist.restore(self.apk)
        self.assertFalse((self.root / 'Common').exists())

    def test_refuses_overwrite_and_extra_libraries(self):
        dest = self.root / self.spec['path']
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b'local changes')
        with self.assertRaisesRegex(ValueError, 'overwrite'):
            dist.restore(self.apk)
        self.assertEqual(dest.read_bytes(), b'local changes')
        dest.write_bytes(self.payload)
        (dest.parent / 'libunexpected.so').write_bytes(b'unreviewed')
        with self.assertRaisesRegex(ValueError, 'Uninventoried'):
            dist.inputs_check()


class ApkVerification(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'scripts/dist').mkdir(parents=True)
        self.cert = 'a' * 64
        (self.root / 'scripts/dist/production-cert.sha256').write_text(self.cert)
        self.apk = self.root / 'example.apk'
        with zipfile.ZipFile(self.apk, 'w') as z:
            z.writestr('lib/arm64-v8a/libg.so', b'compiled')
        for p in [patch.object(dist, 'ROOT', self.root), patch.object(dist, 'version', return_value=('1.2.3-Alpha', 1023)), patch.object(dist, 'inventory', return_value={'files': []}), patch.object(dist, 'abis', return_value=['arm64-v8a']), patch.object(dist, 'sdk_tool', side_effect=lambda name: name)]:
            p.start()
            self.addCleanup(p.stop)
        self.badging = "package: name='tk.glucodata.ng' versionCode='1023' versionName='1.2.3-Alpha-phone'"

    def verify(self, certificate, badging=None):
        with patch.object(dist.subprocess, 'check_output', side_effect=[certificate, badging or self.badging]):
            dist.verify_apk(self.apk, 'mobile', 'release')

    def test_both_apksigner_output_formats(self):
        for prefix in ['Signer #1', 'V2 Signer:']:
            self.verify(prefix + ' certificate SHA-256 digest: ' + self.cert)

    def test_fallback_or_missing_certificate_rejected(self):
        for certificate in ['Signer #1 certificate SHA-256 digest: ' + 'b' * 64, 'no certificate']:
            with self.assertRaisesRegex(ValueError, 'certificate mismatch'):
                self.verify(certificate)

    def test_wrong_version_package_debuggable_rejected(self):
        for badging in [self.badging.replace('1023', '1022'), self.badging.replace('tk.glucodata.ng', 'tk.glucodata.ng.dub'), self.badging + '\napplication-debuggable']:
            with self.assertRaises(ValueError):
                self.verify('Signer #1 certificate SHA-256 digest: ' + self.cert, badging)

    def test_missing_abi_and_incomplete_set_rejected(self):
        with patch.object(dist, 'abis', return_value=['arm64-v8a', 'armeabi-v7a']):
            with self.assertRaisesRegex(ValueError, 'ABI mismatch'):
                self.verify('Signer #1 certificate SHA-256 digest: ' + self.cert)
        with self.assertRaisesRegex(ValueError, 'APK set mismatch'):
            dist.verify_set(self.root, 'all')

    def test_release_tag_mismatch_rejected(self):
        with patch('sys.argv', ['dist.py', 'release-check', '--tag', '1.2.2-Alpha']):
            with self.assertRaisesRegex(ValueError, 'exactly equal'):
                dist.main()


# Import release helper through its normal sibling-module lookup.
import sys
sys.path.insert(0, str(BASE))
release = module('release-preflight')


class ReleasePreflight(unittest.TestCase):
    def run_preflight(self, tag='1.2.3-Alpha', refs=None, releases=None, previous_code=1022):
        from io import BytesIO
        with patch.dict('os.environ', {'TAG': tag, 'GITHUB_REPOSITORY': 'ctqvva/JugglucoNG'}), \
             patch.object(release, 'version', return_value=('1.2.3-Alpha', 1023)), \
             patch.object(release.subprocess, 'check_output', side_effect=[json.dumps([refs or []]), json.dumps([releases or []])]), \
             patch.object(release.urllib.request, 'urlopen', return_value=BytesIO(json.dumps({'versionCode': previous_code}).encode())):
            release.main()

    def test_existing_tag_and_draft_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Tag already exists'):
            self.run_preflight(refs=[{'ref': 'refs/tags/1.2.3-Alpha'}])
        with self.assertRaisesRegex(ValueError, 'including draft'):
            self.run_preflight(releases=[{'tag_name': '1.2.3-Alpha', 'draft': True}])

    def test_version_mismatch_and_regression_rejected(self):
        with self.assertRaisesRegex(ValueError, 'exactly equal'):
            self.run_preflight(tag='v1.2.3-Alpha')
        for previous in [1023, 1024]:
            with self.assertRaisesRegex(ValueError, 'exceed every'):
                self.run_preflight(releases=[{'tag_name': '1.2.2-Alpha', 'draft': False, 'assets': [{'name': 'update-manifest.json', 'browser_download_url': 'https://example.invalid/manifest'}]}], previous_code=previous)

    def test_new_increasing_version_accepted(self):
        self.run_preflight(releases=[{'tag_name': '1.2.2-Alpha', 'draft': False, 'assets': [{'name': 'update-manifest.json', 'browser_download_url': 'https://example.invalid/manifest'}]}])


if __name__ == '__main__':
    unittest.main()
