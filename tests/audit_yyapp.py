# -*- coding: utf-8 -*-
"""只读审查 YYAPP 的实际 Java 类，所有夹具写到系统临时目录。"""
from pathlib import Path
import hashlib,subprocess,tempfile,zipfile
YY=Path(r'E:\myUnity\YYAPP'); JAVA=Path(r'C:\Program Files\Unity 6000.0.59f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK\bin')
with tempfile.TemporaryDirectory(prefix='yyapp-audit-')as d:
 w=Path(d);s=w/'Audit.java'
 s.write_text('''import java.io.*;import java.nio.file.*;import com.family.englishquest.*;
public class Audit{public static void main(String[]a)throws Exception{
 File root=new File(a[0]),current=new File(root,"www"),incoming=new File(root,"incoming");current.mkdirs();incoming.mkdirs();
 Files.write(new File(current,"index.html").toPath(),"old-working".getBytes("UTF-8"));
 Files.write(new File(incoming,"index.html").toPath(),"new-broken-script".getBytes("UTF-8"));
 UpdateStorage.install(incoming,current);UpdateStorage.recover(root);
 if(new File(root,"www_old").exists())throw new AssertionError("fixture expected old behavior");
 System.out.println("CONFIRMED: startup deletes old resources before checking frontend readiness.");
 UpdatePackage.verifyAndExtract(new File(a[1]),new File(root,"extracted"),Long.parseLong(a[2]),a[3]);
 System.out.println("CONFIRMED: package validator accepts a 33 MiB expanded entry without an extraction size limit.");
}}''',encoding='utf-8')
 src=YY/'android-shell/app/src/main/java/com/family/englishquest'
 subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(w),str(src/'UpdateStorage.java'),str(src/'UpdatePackage.java'),str(s)],check=True)
 core=['index.html','manifest.json','css/style.css','css/learning.css','css/care.css','js/app.js','js/update.js','js/learning.js','js/care.js','data/grade1.js','data/grade2.js','data/grade3.js','data/phonics.js','data/vocabulary.js','data/textbook.js']
 archive=w/'fixture.wgt'
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED)as z:
  for name in core:z.writestr(name,b'fixture')
  z.writestr('large-test-entry.bin',b'0'*(33*1024*1024))
 subprocess.run([str(JAVA/'java.exe'),'-cp',str(w),'Audit',str(w/'state'),str(archive),str(archive.stat().st_size),hashlib.sha256(archive.read_bytes()).hexdigest()],check=True)
