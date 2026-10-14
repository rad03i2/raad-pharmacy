"""Pack only reviewed source and the dependency lock. Never package phone sessions or tokens."""
import argparse
import gzip
import io
import pathlib
import tarfile

FILES = (
    'package.json', 'package-lock.json', 'protocol.mjs', 'store.mjs', 'service.mjs',
    'baileys-adapter.mjs', 'index.mjs', 'verify-runtime.mjs', 'stop-previous.mjs', 'install.sh',
)

def package(output):
    source = pathlib.Path(__file__).resolve().parent
    output = pathlib.Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as packed:
        with tarfile.open(fileobj=packed, mode='w') as archive:
            for name in FILES:
                content = (source / name).read_bytes()
                item = tarfile.TarInfo(name)
                item.size = len(content)
                item.mode = 0o700 if name.endswith('.sh') else 0o600
                item.mtime = 0
                archive.addfile(item, io.BytesIO(content))
    if output.stat().st_size > 75_000:
        raise ValueError('Phone setup archive exceeds the clipboard setup budget')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    package(parser.parse_args().output)
