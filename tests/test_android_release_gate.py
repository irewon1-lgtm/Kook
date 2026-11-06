"""Execute the actual workflow gate against real Git histories, without releasing an APK."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/android-build.yml'


def gate_script(identifier):
    section = WORKFLOW.read_text().split(f'        id: {identifier}\n', 1)[1]
    block = section.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0]
    lines = block.splitlines()
    return '\n'.join(line[10:] for line in lines if line.startswith('          ')) + '\n'


class AndroidReleaseGateTest(unittest.TestCase):
    def evaluate(self, paths=(), event='push', ref='refs/heads/main', identifier='release_gate', deletion=False, parent=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(['git', *args], cwd=root, check=True, capture_output=True, text=True)
            git('init', '-q')
            git('config', 'user.name', 'Release gate regression')
            git('config', 'user.email', 'release-gate@example.invalid')
            (root / 'baseline.txt').write_text('baseline\n')
            if deletion:
                for name in paths:
                    path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('before\n')
            git('add', '.'); git('commit', '-qm', 'baseline')
            if parent:
                for name in paths:
                    path = root / name
                    if deletion:
                        path.unlink()
                    else:
                        path.parent.mkdir(parents=True, exist_ok=True); path.write_text('changed\n')
                git('add', '-A'); git('commit', '--allow-empty', '-qm', 'controlled change')
            output = root / 'gate-output'
            script = gate_script(identifier).replace('${{ github.event_name }}', event)
            self.assertIsNone(re.search(r'\$\{\{', script), 'Unresolved GitHub expression in executed gate')
            subprocess.run(['bash', '-c', script], cwd=root, env={**os.environ, 'GITHUB_REF': ref, 'GITHUB_OUTPUT': str(output)}, check=True, capture_output=True, text=True)
            return dict(line.split('=', 1) for line in output.read_text().splitlines())

    def test_test_pc_ci_and_gallery_changes_do_not_release(self):
        for path in ('app/src/test/java/RegressionTest.kt', 'app/src/androidTest/java/DeviceTest.kt',
                     'web/src/App.tsx', 'scripts/build_pc_publication.py', '.github/workflows/pc-web.yml',
                     '.github/workflows/android-build.yml', 'scripts/build_ui_gallery.py', 'tests/test_ui_gallery.py',
                     'scripts/build_navy_comparison.py', 'tests/test_navy_comparison.py',
                     'docs/design/approved-dark-navy/reference.jpg', 'evidence/real_quant_snapshot.json'):
            with self.subTest(path=path):
                self.assertEqual({'app_changed': 'false', 'ready': 'false'}, self.evaluate([path]))

    def test_actual_release_inputs_still_release(self):
        for path in ('app/src/main/java/Production.kt', 'app/src/main/AndroidManifest.xml', 'app/src/main/res/values/strings.xml',
                     'app/src/release/java/Production.kt', 'app/build.gradle.kts', 'app/proguard-rules.pro', 'app/libs/runtime.jar',
                     'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml', 'buildSrc/src/main/kotlin/Build.kt'):
            with self.subTest(path=path):
                self.assertEqual('true', self.evaluate([path])['ready'])
                self.assertEqual('true', self.evaluate([path], identifier='app_scope')['changed'])

    def test_mixed_test_ci_pc_changes_do_not_release(self):
        paths = ['app/src/test/java/RegressionTest.kt', '.github/workflows/android-build.yml', 'web/src/App.tsx']
        self.assertEqual('false', self.evaluate(paths)['ready'])
        self.assertEqual('true', self.evaluate(paths + ['app/src/main/java/Production.kt'])['ready'])

    def test_test_only_changes_still_run_broad_app_validation(self):
        self.assertEqual('true', self.evaluate(['app/src/test/java/RegressionTest.kt'], identifier='app_scope')['changed'])
        self.assertEqual('true', self.evaluate(['.github/workflows/android-build.yml'], identifier='app_scope')['changed'])

    def test_explicit_manual_main_release_is_preserved(self):
        self.assertEqual('true', self.evaluate(event='workflow_dispatch')['ready'])
        self.assertEqual('true', self.evaluate(event='workflow_dispatch', parent=False)['ready'])

    def test_feature_branch_and_pull_request_cannot_release(self):
        self.assertEqual('false', self.evaluate(['app/src/main/java/Production.kt'], ref='refs/heads/feature')['ready'])
        self.assertEqual('false', self.evaluate(event='workflow_dispatch', ref='refs/heads/feature')['ready'])
        self.assertEqual('false', self.evaluate(['app/src/main/java/Production.kt'], event='pull_request', ref='refs/pull/1/merge')['ready'])

    def test_deleted_production_input_is_still_a_release_change(self):
        self.assertEqual('true', self.evaluate(['app/src/main/java/Production.kt'], deletion=True)['ready'])

    def test_missing_comparison_history_does_not_implicitly_release(self):
        self.assertEqual('false', self.evaluate(parent=False)['ready'])


if __name__ == '__main__':
    unittest.main()
