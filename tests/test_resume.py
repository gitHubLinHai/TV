"""真实HTTP和JVM测试续传：包括杀进程后重新运行，不用模拟下载循环。"""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import hashlib
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(r'C:\Program Files\Unity 6000.0.59f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK\bin')
DATA = bytes(range(251)) * 2048
HASH = hashlib.sha256(DATA).hexdigest()
requests = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        requested = self.headers.get('Range')
        requests.append((self.path, requested))
        offset = int(requested[6:-1]) if requested else 0
        if self.path == '/unsat' and requested:
            self.send_response(416); self.end_headers(); return
        if self.path == '/ignore':
            offset = 0
        body = DATA[offset:]
        self.send_response(206 if offset else 200)
        if offset:
            start = offset + (1 if self.path == '/bad-range' else 0)
            total = len(DATA) + (1 if self.path == '/wrong-total' else 0)
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, len(DATA) - 1, total))
        if self.path != '/oversize':
            self.send_header('Content-Length', str(len(body) + (1 if self.path == '/wrong-length' else 0)))
        if self.path == '/encoding':
            self.send_header('Content-Encoding', 'gzip')
        self.end_headers()
        try:
            if self.path == '/short':
                self.wfile.write(body[:65536]); self.wfile.flush(); self.close_connection = True
            elif self.path == '/slow':
                for i in range(0, len(body), 4096):
                    self.wfile.write(body[i:i+4096]); self.wfile.flush(); time.sleep(.02)
            else:
                self.wfile.write(body + (b'extra' if self.path == '/oversize' else b''))
        except (BrokenPipeError, ConnectionResetError):
            pass


HARNESS = '''import java.io.*;
import java.net.*;
import com.seagull.tv.UpdateDownload;
public class ResumeTest {
 public static void main(String[] args) throws Exception {
  UpdateDownload.fetch(new URL(args[0]), new File(args[1]), Long.parseLong(args[2]), args[3],
   url -> (HttpURLConnection)url.openConnection(), () -> {},
   (done,total) -> { System.out.println(done); System.out.flush(); });
 }
}'''

with tempfile.TemporaryDirectory(prefix='yyapp-resume-') as folder:
    work = Path(folder)
    (work / 'ResumeTest.java').write_text(HARNESS, encoding='utf-8')
    subprocess.run([str(JAVA/'javac.exe'), '-encoding', 'UTF-8', '-d', str(work),
                    str(ROOT/'android-shell/app/src/main/java/com/seagull/tv/UpdateDownload.java'), str(work/'ResumeTest.java')], check=True)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    archive = work/'update.wgt'
    identity = work/'update.wgt.id'
    cases = 0

    def command(route):
        return [str(JAVA/'java.exe'), '-cp', str(work), 'ResumeTest',
                'http://127.0.0.1:%d%s' % (server.server_port, route), str(archive), str(len(DATA)), HASH]

    def seed(count=12345, valid=True):
        archive.write_bytes(DATA[:count])
        identity.write_bytes((str(len(DATA))+'\n'+(HASH if valid else 'b'*64)).encode('ascii'))
        requests.clear()

    def run(route, valid=True):
        global cases
        result = subprocess.run(command(route), capture_output=True, timeout=25)
        cases += 1
        assert (result.returncode == 0) == valid, result.stderr.decode(errors='replace')
        if valid:
            assert archive.read_bytes() == DATA

    try:
        seed(); run('/ok')
        assert requests[-1][1] == 'bytes=12345-'
        seed(); run('/ignore')
        seed(); run('/bad-range', False); assert archive.read_bytes() == DATA[:12345]
        seed(); run('/wrong-total', False); assert archive.read_bytes() == DATA[:12345]
        seed(); run('/wrong-length', False); assert archive.read_bytes() == DATA[:12345]
        seed(); run('/encoding', False); assert archive.read_bytes() == DATA[:12345]
        seed(); run('/unsat'); assert [r[1] for r in requests] == ['bytes=12345-', None]
        seed(0); run('/short', False)
        downloaded = archive.stat().st_size
        assert 0 < downloaded < len(DATA)
        run('/ok'); assert requests[-1][1] == 'bytes=%d-' % downloaded
        seed(valid=False); run('/ok'); assert requests[-1][1] is None
        seed(len(DATA)); run('/ok'); assert not requests, '完整片段不能重复下载'
        seed(0); run('/oversize', False); assert not archive.exists()
        seed(); identity.write_text('broken', encoding='ascii'); run('/ok'); assert requests[-1][1] is None
        # 真正终止JVM进程，另一个进程读取同一磁盘片段后续传。
        seed(0)
        process = subprocess.Popen(command('/slow'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        for line in process.stdout:
            if int(line.strip()) > 0:
                break
        process.kill(); process.communicate(timeout=5)
        downloaded = archive.stat().st_size
        assert 0 < downloaded < len(DATA)
        run('/ok'); assert requests[-1][1] == 'bytes=%d-' % downloaded
        print('断点续传通过：%d个真实HTTP/JVM用例，包括206、忽略Range、416、非法范围/长度/编码、短包、超大包、换包重置、完整包复用，以及杀进程后按磁盘字节继续。' % cases)
    finally:
        server.shutdown(); server.server_close()
