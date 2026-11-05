/*
 * BitChord.exe, the portable release's launcher (fork, branch pc).
 *
 * Starts the Java the folder carries (runtime\bin\javaw.exe) with the arguments in BitChord.args,
 * one per line, "{app}" standing for this exe's folder. desktopApp/build.gradle.kts writes that
 * file, so the JVM flags, the classpath and the main class stay in one place; this program only
 * finds its folder, quotes the arguments and starts Java. A real exe rather than the earlier .bat:
 * no console window flashing up, the app's icon and version in Explorer.
 *
 *   BitChord.exe [args...]        starts the app; extra arguments go to the app, as %* did
 *   BitChord.exe --log [args...]  the same with java.exe, its output in data\bitchord.log
 *
 * Built with MinGW (docker/pc-launcher.sh): -municode -mwindows, so the entry is wWinMain and
 * every path is UTF-16, which is what makes a folder like "BitChord Portable" or a non-ASCII
 * user name work.
 *
 * Java's own launcher is not: javaw.exe reads its arguments and its own location in the ANSI code
 * page, so a folder with characters outside it (Cyrillic on a Western system, say) reached Java as
 * question marks and Java did not start. See java_folder().
 */
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define PATH_CAP 32768

typedef struct {
    wchar_t *text;
    size_t length;
    size_t capacity;
} Buffer;

/* Never returns (ExitProcess), so the compiler knows nothing after a failed check runs. */
static __attribute__((noreturn)) void fail(const wchar_t *message, const wchar_t *detail) {
    wchar_t text[PATH_CAP + 512];
    _snwprintf(text, sizeof text / sizeof text[0] - 1, L"%ls\n\n%ls", message, detail ? detail : L"");
    text[sizeof text / sizeof text[0] - 1] = 0;
    MessageBoxW(NULL, text, L"BitChord", MB_OK | MB_ICONERROR);
    ExitProcess(1);
}

static void append(Buffer *buffer, const wchar_t *text, size_t count) {
    if (buffer->length + count + 1 > buffer->capacity) {
        size_t next = buffer->capacity ? buffer->capacity : 1024;
        while (buffer->length + count + 1 > next) next *= 2;
        wchar_t *grown = realloc(buffer->text, next * sizeof(wchar_t));
        if (!grown) fail(L"Out of memory.", NULL);
        buffer->text = grown;
        buffer->capacity = next;
    }
    memcpy(buffer->text + buffer->length, text, count * sizeof(wchar_t));
    buffer->length += count;
    buffer->text[buffer->length] = 0;
}

static void append_z(Buffer *buffer, const wchar_t *text) { append(buffer, text, wcslen(text)); }

static void append_repeat(Buffer *buffer, wchar_t c, size_t count) {
    while (count--) append(buffer, &c, 1);
}

/*
 * One argument as CommandLineToArgvW (and Java's launcher) will split it back: quoted when it
 * holds a space, a quote, or a wildcard (so "lib\*" is never globbed), backslashes doubled only
 * where they precede a quote.
 */
static void append_argument(Buffer *buffer, const wchar_t *argument) {
    if (buffer->length) append_z(buffer, L" ");
    if (*argument && !wcspbrk(argument, L" \t\n\v\"*?")) {
        append_z(buffer, argument);
        return;
    }
    append_z(buffer, L"\"");
    for (const wchar_t *p = argument;; ++p) {
        size_t slashes = 0;
        while (*p == L'\\') {
            ++p;
            ++slashes;
        }
        if (!*p) {
            append_repeat(buffer, L'\\', slashes * 2);
            break;
        }
        if (*p == L'"') {
            append_repeat(buffer, L'\\', slashes * 2 + 1);
            append(buffer, p, 1);
        } else {
            append_repeat(buffer, L'\\', slashes);
            append(buffer, p, 1);
        }
    }
    append_z(buffer, L"\"");
}

/* A line of BitChord.args with every "{app}" replaced by the folder. */
static void append_line(Buffer *buffer, const wchar_t *line, size_t length, const wchar_t *folder) {
    Buffer expanded = {0};
    static const wchar_t token[] = L"{app}";
    const size_t tokenLength = sizeof token / sizeof token[0] - 1;
    size_t i = 0;
    while (i < length) {
        if (length - i >= tokenLength && wcsncmp(line + i, token, tokenLength) == 0) {
            append_z(&expanded, folder);
            i += tokenLength;
        } else {
            append(&expanded, line + i, 1);
            i++;
        }
    }
    append(&expanded, L"", 0);
    append_argument(buffer, expanded.text);
    free(expanded.text);
}

/* BitChord.args as UTF-16; a UTF-8 BOM is allowed. */
static wchar_t *read_arguments(const wchar_t *path) {
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) fail(L"BitChord.args is missing beside BitChord.exe. Unzip the whole folder again.", path);
    LARGE_INTEGER size;
    if (!GetFileSizeEx(file, &size) || size.QuadPart > 1024 * 1024) fail(L"BitChord.args could not be read.", path);
    DWORD length = (DWORD)size.QuadPart, read = 0;
    char *bytes = malloc(length + 1);
    if (!bytes || !ReadFile(file, bytes, length, &read, NULL) || read != length) fail(L"BitChord.args could not be read.", path);
    CloseHandle(file);
    char *start = bytes;
    if (length >= 3 && (unsigned char)bytes[0] == 0xEF && (unsigned char)bytes[1] == 0xBB && (unsigned char)bytes[2] == 0xBF) {
        start += 3;
        length -= 3;
    }
    int wide = length ? MultiByteToWideChar(CP_UTF8, 0, start, (int)length, NULL, 0) : 0;
    /* Empty, Java would start with no main class and, as javaw, end without a word. */
    if (wide == 0) fail(L"BitChord.args is empty or damaged. Unzip the whole folder again.", path);
    wchar_t *text = malloc(((size_t)wide + 1) * sizeof(wchar_t));
    if (!text) fail(L"Out of memory.", NULL);
    if (wide) MultiByteToWideChar(CP_UTF8, 0, start, (int)length, text, wide);
    text[wide] = 0;
    free(bytes);
    return text;
}

static int is_space(wchar_t c) { return c == L' ' || c == L'\t' || c == L'\r' || c == L'\n'; }

/* Whether text survives the ANSI code page unchanged, as Java's launcher will read it. */
static int ansi_safe(const wchar_t *text) {
    /* A system set to UTF-8 as its code page loses nothing (and refuses the flags below). */
    if (GetACP() == CP_UTF8) return 1;
    BOOL lossy = FALSE;
    int bytes = WideCharToMultiByte(CP_ACP, WC_NO_BEST_FIT_CHARS, text, -1, NULL, 0, NULL, &lossy);
    return bytes > 0 && !lossy;
}

/*
 * fork: the folder as Java gets to see it. Unchanged when the ANSI code page can spell it;
 * otherwise its 8.3 short name (all ASCII) for javaw.exe, the working folder and "{app}", which
 * Windows keeps for most folders on the system drive. Where the volume has no short names,
 * "{app}" becomes "." instead: the working folder is this folder, and Java resolves relative paths
 * against it (ForkPortable.absolutePaths makes them absolute first thing). Java's own launcher may
 * still fail there, since it finds jvm.dll from its own ANSI path; nothing here can change that.
 */
static const wchar_t *java_folder(const wchar_t *folder, const wchar_t **app) {
    static wchar_t shortFolder[PATH_CAP];
    *app = folder;
    if (ansi_safe(folder)) return folder;
    DWORD got = GetShortPathNameW(folder, shortFolder, PATH_CAP);
    if (got > 0 && got < PATH_CAP && ansi_safe(shortFolder)) {
        *app = shortFolder;
        return shortFolder;
    }
    *app = L".";
    return folder;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR commandLine, int show) {
    (void)instance;
    (void)previous;
    (void)commandLine;
    (void)show;

    /* This exe's folder, without the trailing backslash. */
    static wchar_t folder[PATH_CAP];
    DWORD got = GetModuleFileNameW(NULL, folder, PATH_CAP);
    if (got == 0 || got >= PATH_CAP) fail(L"BitChord could not find its own folder.", NULL);
    wchar_t *slash = wcsrchr(folder, L'\\');
    if (!slash) fail(L"BitChord could not find its own folder.", folder);
    *slash = 0;

    /* fork: what Java is started from and what "{app}" becomes; see java_folder. */
    const wchar_t *app = folder;
    const wchar_t *home = java_folder(folder, &app);

    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    int logMode = 0;
    for (int i = 1; argv && i < argc; i++) {
        if (wcscmp(argv[i], L"--log") == 0) logMode = 1;
    }

    Buffer path = {0};
    append_z(&path, home);
    append_z(&path, logMode ? L"\\runtime\\bin\\java.exe" : L"\\runtime\\bin\\javaw.exe");
    if (GetFileAttributesW(path.text) == INVALID_FILE_ATTRIBUTES) {
        fail(L"BitChord's Java is missing (the runtime folder). Unzip the whole folder again.", path.text);
    }

    /* The command line: Java itself, then BitChord.args line by line, then what we were given. */
    Buffer command = {0};
    append_argument(&command, path.text);
    Buffer argsPath = {0};
    append_z(&argsPath, folder);
    append_z(&argsPath, L"\\BitChord.args");
    wchar_t *lines = read_arguments(argsPath.text);
    for (wchar_t *line = lines; *line;) {
        wchar_t *end = line;
        while (*end && *end != L'\n') end++;
        wchar_t *first = line, *last = end;
        while (first < last && is_space(*first)) first++;
        while (last > first && is_space(last[-1])) last--;
        if (last > first && *first != L'#') append_line(&command, first, (size_t)(last - first), app);
        line = *end ? end + 1 : end;
    }
    free(lines);
    for (int i = 1; argv && i < argc; i++) {
        if (wcscmp(argv[i], L"--log") != 0) append_argument(&command, argv[i]);
    }
    if (argv) LocalFree(argv);

    STARTUPINFOW startup;
    ZeroMemory(&startup, sizeof startup);
    startup.cb = sizeof startup;
    PROCESS_INFORMATION process;
    ZeroMemory(&process, sizeof process);
    HANDLE log = INVALID_HANDLE_VALUE;
    DWORD flags = 0;
    if (logMode) {
        /* data\ is where the app keeps everything; the log goes there too. */
        Buffer logPath = {0};
        append_z(&logPath, folder);
        append_z(&logPath, L"\\data");
        CreateDirectoryW(logPath.text, NULL);
        append_z(&logPath, L"\\bitchord.log");
        SECURITY_ATTRIBUTES inherit = {sizeof inherit, NULL, TRUE};
        log = CreateFileW(logPath.text, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, &inherit,
                          CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (log == INVALID_HANDLE_VALUE) fail(L"BitChord could not write its log.", logPath.text);
        free(logPath.text);
        startup.dwFlags = STARTF_USESTDHANDLES;
        startup.hStdInput = NULL;
        startup.hStdOutput = log;
        startup.hStdError = log;
        flags = CREATE_NO_WINDOW;
    }

    if (!CreateProcessW(path.text, command.text, NULL, NULL, logMode ? TRUE : FALSE, flags, NULL, home, &startup, &process)) {
        wchar_t detail[64];
        _snwprintf(detail, 63, L"Windows error %lu", (unsigned long)GetLastError());
        detail[63] = 0;
        fail(L"BitChord could not start its Java.", detail);
    }
    /*
     * This exe was the foreground process (the listener just opened it) and is about to end:
     * passing that on lets the app's window come up in front instead of behind Explorer.
     */
    AllowSetForegroundWindow(process.dwProcessId);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    if (log != INVALID_HANDLE_VALUE) CloseHandle(log);
    free(path.text);
    free(command.text);
    free(argsPath.text);
    return 0;
}
