READER_SOURCE = r'''
import json
import sys
for line in sys.stdin:
    print(json.dumps(dict(id=1, engine_info=ENGINE_INFO)), flush=True)
'''
RUNTIME_GENERATION = 150
READER_SOURCE = ('RUNTIME_GENERATION = ' + str(RUNTIME_GENERATION) + '\nCONNECTOR_SOURCES = ' +
                 repr(globals().get('CONNECTOR_SOURCES', {})) + '\n' + READER_SOURCE)

if __name__ == '__main__':
    import base64 as _base64
    import fcntl
    import hashlib as _hashlib
    import json as _json
    import os as _os
    import stat as _stat
    import sys as _sys
    import tempfile as _tempfile

    def _select_runtime(source):
        generation = RUNTIME_GENERATION
        digest = _hashlib.sha256(source).hexdigest()
        home = _os.path.expanduser('~')
        home_fd = _os.open(home, _os.O_RDONLY | _os.O_DIRECTORY | _os.O_NOFOLLOW)
        try:
            try:
                _os.mkdir('.multiprompt-runtime', 0o700, dir_fd=home_fd)
            except FileExistsError:
                pass
            directory = _os.open('.multiprompt-runtime', _os.O_RDONLY | _os.O_DIRECTORY | _os.O_NOFOLLOW, dir_fd=home_fd)
        finally:
            _os.close(home_fd)
        try:
            def check(fd, mode):
                st = _os.fstat(fd)
                if st.st_uid != _os.geteuid() or _stat.S_IMODE(st.st_mode) != mode:
                    raise ValueError('unsafe runtime permissions')
            check(directory, 0o700)
            lock = _os.open('lock', _os.O_RDWR | _os.O_CREAT | _os.O_NOFOLLOW, 0o600, dir_fd=directory)
            try:
                check(lock, 0o600)
                if not _stat.S_ISREG(_os.fstat(lock).st_mode):
                    raise ValueError('invalid lock')
                fcntl.flock(lock, fcntl.LOCK_EX)
                def read(name, limit):
                    fd = _os.open(name, _os.O_RDONLY | _os.O_NOFOLLOW | _os.O_NONBLOCK, dir_fd=directory)
                    try:
                        check(fd, 0o600)
                        st = _os.fstat(fd)
                        if not _stat.S_ISREG(st.st_mode) or st.st_size > limit:
                            raise ValueError('invalid runtime file')
                        with _os.fdopen(fd, 'rb', closefd=False) as stream:
                            return stream.read(limit + 1)
                    finally:
                        _os.close(fd)
                def publish(name, data):
                    fd, path = _tempfile.mkstemp(prefix='.tmp-', dir=_os.path.join(home, '.multiprompt-runtime'))
                    try:
                        with _os.fdopen(fd, 'wb') as stream:
                            stream.write(data)
                            stream.flush()
                            _os.fsync(stream.fileno())
                        _os.replace(path, name, dst_dir_fd=directory)
                        _os.fsync(directory)
                    finally:
                        if _os.path.exists(path):
                            _os.unlink(path)
                runtime_root = _os.path.join(home, '.multiprompt-runtime')

                def relink(name, target):
                    path = _os.path.join(runtime_root, name)
                    try:
                        _os.unlink(path)
                    except FileNotFoundError:
                        pass
                    _os.symlink(target, path)

                def publish_named_tool(name, payload):
                    digest = _hashlib.sha256(payload).hexdigest()
                    stem = digest + '-' + name
                    publish(stem, payload)
                    relink(name, stem)
                    return stem

                def publish_tools(data):
                    try:
                        reader_digest = _hashlib.sha256(data).hexdigest()
                        publish(reader_digest + '.py', data)
                        relink('reader.py', reader_digest + '.py')
                    except OSError:
                        pass
                    mp_send_source = globals().get('MP_SEND_SOURCE')
                    if isinstance(mp_send_source, str) and mp_send_source:
                        try:
                            published = publish_named_tool('mp-send', _base64.b64decode(mp_send_source))
                            _os.chmod(published, 0o700, dir_fd=directory)
                        except (ValueError, OSError):
                            pass
                    feedback_source = globals().get('FEEDBACK_SOURCE')
                    if isinstance(feedback_source, str) and feedback_source:
                        try:
                            publish_named_tool('multibot-feedback', _base64.b64decode(feedback_source))
                            _os.chmod('multibot-feedback', 0o700, dir_fd=directory)
                        except (ValueError, OSError, NotImplementedError):
                            pass
                    helper_source = globals().get('MP_HELPER_SOURCE')
                    if isinstance(helper_source, str) and helper_source:
                        try:
                            publish_named_tool('mp-helper', _base64.b64decode(helper_source))
                            _os.chmod('mp-helper', 0o700, dir_fd=directory)
                        except (ValueError, OSError, NotImplementedError):
                            pass
                    shell_source = globals().get('MP_SHELL_SOURCE')
                    if isinstance(shell_source, str) and shell_source:
                        try:
                            publish_named_tool('mp-shell', _base64.b64decode(shell_source))
                            _os.chmod('mp-shell', 0o700, dir_fd=directory)
                        except (ValueError, OSError, NotImplementedError):
                            pass
                    sessions_source = globals().get('MP_SESSIONS_SOURCE')
                    if isinstance(sessions_source, str) and sessions_source:
                        try:
                            publish_named_tool('mp-sessions', _base64.b64decode(sessions_source))
                            _os.chmod('mp-sessions', 0o700, dir_fd=directory)
                        except (ValueError, OSError, NotImplementedError):
                            pass
                    layout_source = globals().get('MP_LAYOUT_SOURCE')
                    if isinstance(layout_source, str) and layout_source:
                        try:
                            relink('mp-layout', publish_named_tool('mp-layout.mjs', _base64.b64decode(layout_source)))
                            _os.chmod('mp-layout', 0o700, dir_fd=directory)
                        except (ValueError, OSError, NotImplementedError):
                            pass
                    fresh_source = globals().get('MP_FRESH_SOURCE')
                    if isinstance(fresh_source, str) and fresh_source:
                        try:
                            published = publish_named_tool('mp-fresh', _base64.b64decode(fresh_source))
                            _os.chmod(published, 0o700, dir_fd=directory)
                        except (ValueError, OSError):
                            pass
                    zsb_source = globals().get('ZSB_SOURCE')
                    if isinstance(zsb_source, str) and zsb_source:
                        try:
                            publish_named_tool('mp-zsb', _base64.b64decode(zsb_source))
                            _os.chmod('mp-zsb', 0o700, dir_fd=directory)
                        except (ValueError, OSError, NotImplementedError):
                            pass
                missing = object()
                try:
                    active = _json.loads(read('active.json', 4096))
                except FileNotFoundError:
                    active = missing
                if active is not missing:
                    if (not isinstance(active, dict) or set(active) != {'protocol', 'generation', 'sha256'} or
                            type(active['protocol']) is not int or active['protocol'] != 1 or
                            type(active['generation']) is not int or active['generation'] < 1 or
                            not isinstance(active['sha256'], str) or len(active['sha256']) != 64 or
                            any(c not in '0123456789abcdef' for c in active['sha256'])):
                        raise ValueError('invalid runtime selection')
                    installed = read(active['sha256'] + '.py', 4 * 1024 * 1024)
                    if _hashlib.sha256(installed).hexdigest() != active['sha256']:
                        raise ValueError('corrupt runtime')
                    if active['generation'] > generation:
                        publish_tools(installed)
                        return installed, active
                    if active['generation'] == generation:
                        if active['sha256'] != digest:
                            publish(digest + '.py', source)
                            active = dict(protocol=1, generation=generation, sha256=digest)
                            publish('active.json', _json.dumps(active, separators=(',', ':')).encode())
                            publish_tools(source)
                            return source, active
                        publish_tools(installed)
                        return installed, active
                try:
                    installed = read(digest + '.py', 4 * 1024 * 1024)
                except FileNotFoundError:
                    publish(digest + '.py', source)
                else:
                    if installed != source:
                        raise ValueError('corrupt runtime')
                active = dict(protocol=1, generation=generation, sha256=digest)
                publish('active.json', _json.dumps(active, separators=(',', ':')).encode())
                publish_tools(source)
                return source, active
            finally:
                _os.close(lock)
        finally:
            _os.close(directory)

    try:
        _selected, _info = _select_runtime(READER_SOURCE.encode('utf-8'))
        _worker_source = ('ENGINE_INFO = ' + repr(_info) + '\nMULTIBOT_APP_VERSION = ' +
                          repr(globals().get('MULTIBOT_APP_VERSION', '')) + '\nRUNTIME_GENERATION = ' +
                          str(RUNTIME_GENERATION) + '\n' + _selected.decode('utf-8'))
        READER_SOURCE = 'READER_SOURCE = ' + repr(_worker_source) + '\n' + _worker_source
    except (OSError, ValueError, UnicodeError):
        def _error(line):
            try:
                request = _json.loads(line)
                rid = request.get('id', 0)
                if type(rid) is not int or rid < 0:
                    rid = 0
            except (ValueError, AttributeError, TypeError):
                rid = 0
            wire = f'@mp\t{rid}\thistory\terror\t-\t0\n@@MP_END@@\n'
            return _json.dumps(dict(id=rid, wire=wire), separators=(',', ':'))
        if _sys.argv[1:] == ['--serve']:
            for _line in _sys.stdin.buffer:
                print(_error(_line), flush=True)
        elif '--watch' in _sys.argv[1:]:
            print(_error(_sys.stdin.buffer.readline()), flush=True)
        else:
            print('@mp\t0\thistory\terror\t-\t0\n@@MP_END@@')
        _sys.exit(1)

exec(compile(READER_SOURCE, '<chat-history-reader>', 'exec'))
