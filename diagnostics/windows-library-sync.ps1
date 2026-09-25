$ErrorActionPreference = 'Stop'
$work = Join-Path $env:RUNNER_TEMP ('library-sync-' + [guid]::NewGuid())
New-Item -ItemType Directory $work | Out-Null
try {
  $artifacts = @(
    'org/apache/lucene/lucene-core/10.3.1/lucene-core-10.3.1.jar',
    'org/apache/kafka/kafka-clients/4.1.0/kafka-clients-4.1.0.jar',
    'org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar'
  )
  foreach ($artifact in $artifacts) {
    $url = 'https://repo.maven.apache.org/maven2/' + $artifact
    $destination = Join-Path $work ([IO.Path]::GetFileName($artifact))
    Invoke-WebRequest $url -OutFile $destination
    Invoke-WebRequest ($url + '.sha512') -OutFile ($destination + '.sha512')
    $expected = (Get-Content ($destination + '.sha512') -Raw).Trim().Split(' ')[0]
    if ((Get-FileHash $destination -Algorithm SHA512).Hash -ine $expected) { throw "Checksum mismatch: $artifact" }
    Write-Host "LIBRARY $artifact SHA512=$expected"
  }
@'
import java.nio.channels.FileChannel;
import java.nio.file.*;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.IOUtils;
import org.apache.kafka.common.utils.Utils;
public class LibrarySyncProbe {
  interface Action { void run() throws Exception; }
  static void observe(String name, Action action) {
    try { action.run(); System.out.println(name + " RETURNED"); }
    catch (Exception e) { System.out.println(name + " THREW " + e); }
  }
  public static void main(String[] args) throws Exception {
    Path dir = Files.createDirectory(Path.of(args[0], "data"));
    Path file = Files.writeString(dir.resolve("file"), "durability probe");
    System.out.println("ENV " + System.getProperty("os.name") + " " + Runtime.version() + " " + Files.getFileStore(dir).type());
    observe("JDK directory open+force", () -> {
      try (FileChannel c = FileChannel.open(dir, StandardOpenOption.READ)) { c.force(true); }
    });
    observe("Lucene IOUtils.fsync directory", () -> IOUtils.fsync(dir, true));
    observe("Lucene FSDirectory.syncMetaData", () -> {
      try (NIOFSDirectory d = new NIOFSDirectory(dir)) { d.syncMetaData(); }
    });
    observe("Kafka Utils.flushDirIfExists", () -> Utils.flushDirIfExists(dir));
    observe("Lucene IOUtils.fsync regular file", () -> IOUtils.fsync(file, false));
    observe("JDK regular file WRITE+force", () -> {
      try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) { c.force(true); }
    });
  }
}
'@ | Set-Content (Join-Path $work 'LibrarySyncProbe.java')
  & java --illegal-native-access=deny --class-path "$work/*" (Join-Path $work 'LibrarySyncProbe.java') $work
  if ($LASTEXITCODE -ne 0) { throw 'Library diagnostic failed' }
} finally {
  Remove-Item -Recurse -Force $work
}
