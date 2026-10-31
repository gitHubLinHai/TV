"""固定版本的 Media3 依赖；从 Google Maven / Maven Central 下载并缓存。"""
from pathlib import Path
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / 'work/player-deps'
GOOGLE = 'https://dl.google.com/dl/android/maven2/'
CENTRAL = 'https://repo.maven.apache.org/maven2/'

def prepare():
    CACHE.mkdir(parents=True, exist_ok=True)
    deps = [('androidx.media3', a, '1.5.1', 'aar') for a in
            ['media3-common', 'media3-container', 'media3-datasource', 'media3-decoder',
             'media3-extractor', 'media3-database', 'media3-exoplayer', 'media3-exoplayer-hls', 'media3-ui']]
    deps += [('androidx.annotation', 'annotation-jvm', '1.6.0', 'jar'),
             ('androidx.annotation', 'annotation-experimental', '1.3.1', 'aar'),
             ('androidx.collection', 'collection', '1.2.0', 'jar'),
             ('androidx.core', 'core', '1.8.0', 'aar'),
             ('androidx.exifinterface', 'exifinterface', '1.3.6', 'aar'),
             ('androidx.versionedparcelable', 'versionedparcelable', '1.1.1', 'aar'),
             ('androidx.lifecycle', 'lifecycle-runtime', '2.3.1', 'aar'),
             ('androidx.lifecycle', 'lifecycle-common', '2.3.1', 'jar'),
             ('androidx.arch.core', 'core-common', '2.1.0', 'jar'),
             ('com.google.guava', 'guava', '33.3.1-android', 'jar'),
             ('com.google.guava', 'failureaccess', '1.0.2', 'jar'),
             ('com.google.code.findbugs', 'jsr305', '3.0.2', 'jar'),
             ('org.checkerframework', 'checker-qual', '3.43.0', 'jar'),
             ('com.google.errorprone', 'error_prone_annotations', '2.28.0', 'jar'),
             ('com.google.j2objc', 'j2objc-annotations', '3.0.0', 'jar')]
    jars, resources, packages = [], [], []
    for group, name, version, kind in deps:
        stem = name + '-' + version
        target = CACHE / (stem + '.' + kind)
        if not target.exists():
            url = (GOOGLE if group.startswith('androidx.') else CENTRAL) + group.replace('.', '/') + '/' + name + '/' + version + '/' + target.name
            with urlopen(url, timeout=30) as response:
                content = response.read()
            # 验证归档完整后才进入缓存。
            import io
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                if archive.testzip(): raise ValueError('损坏的依赖: ' + name)
            target.write_bytes(content)
        if kind == 'jar': jars.append(target); continue
        folder = CACHE / stem
        if not (folder / 'classes.jar').exists():
            with zipfile.ZipFile(target) as archive: archive.extractall(folder)
        jars.append(folder / 'classes.jar')
        if (folder / 'res').exists() and any((folder / 'res').rglob('*.*')):
            resources.append(folder / 'res')
            import xml.etree.ElementTree as ET
            packages.append(ET.parse(folder / 'AndroidManifest.xml').getroot().attrib['package'])
    return jars, resources, packages
