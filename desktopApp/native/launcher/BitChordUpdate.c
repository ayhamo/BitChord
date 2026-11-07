/*
 * BitChordUpdate.exe, the portable app's updater (fork, ForkUpdater.kt).
 *
 *   BitChordUpdate.exe <app folder> <staged folder> <pid> [--log] [--started=<ms>]
 *
 * The app has already downloaded the release zip, checked its SHA-256 and unzipped it into
 * <app folder>\data\update\staged\ (all of that in Java, while it still ran). It then copies this
 * exe into data\update\ (the copy in the app folder is one of the files being replaced), starts it
 * and quits through its normal Quit. This program:
 *
 *   1. shows a small window ("Updating BitChord…") and waits for <pid> to exit; after two minutes
 *      it asks (Retry / Cancel) instead of waiting for ever or moving files under a running app.
 *      --started gives the app's start time (ms since 1970): a process under that PID that started
 *      at another time is a later program that got the number after BitChord exited, not waited on;
 *   2. moves the old program files (ITEMS below) into data\update\old\ and the staged ones into
 *      the app folder. Moves, not copies: everything is on one volume, so each is a rename and the
 *      folder is never half old, half new for long. data\ itself is never touched: only the names
 *      in ITEMS are moved, and the staged folder must be inside data\update\;
 *   3. if any move fails, puts back what it had moved (the new ones to staged\, the old ones from
 *      old\), says so, and starts the old app;
 *   4. starts the new BitChord.exe (with --log when the old one ran with it). The new app deletes
 *      data\update\old and staged once it has drawn its first frame; a start that never gets that
 *      far leaves old\ for a manual roll-back.
 *
 * Every step is written to data\update\update.log, which holds the last update only. Ideas from Desktop Revived 1.9.1's updater (#106:
 * wait for the PID, back up, roll back, delete the backup only after a confirmed start); not its
 * PowerShell host or its swap of the whole folder, since here data\ lives inside the folder.
 *
 * Wide-character Win32 throughout (-municode), so a folder name outside the ANSI code page works.
 */
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define PATH_CAP 32768
#define WM_APP_STATUS (WM_APP + 1)
#define WM_APP_DONE (WM_APP + 2)
#define WAIT_FOR_EXIT_MS 120000

/* The program files of the portable folder: what the zip holds besides nothing else. Never data. */
static const wchar_t *const ITEMS[] = {
    L"BitChord.exe", L"BitChordUpdate.exe", L"BitChord.args", L"BitChord (log).bat", L"lib", L"runtime",
};
#define ITEM_COUNT (sizeof ITEMS / sizeof ITEMS[0])

static wchar_t appDir[PATH_CAP], stagedDir[PATH_CAP], updateDir[PATH_CAP], oldDir[PATH_CAP];
static DWORD appPid;
static ULONGLONG appStarted; /* the app's start, ms since 1970; 0 when not given (an older app) */
static int logMode;
static HANDLE logFile = INVALID_HANDLE_VALUE;
static HWND window, heading, status, progress;
static HFONT bodyFont, headingFont;
static HBRUSH background;
static volatile LONG finished;

/* ---- log ------------------------------------------------------------------------------------- */

static void log_line(const wchar_t *format, ...) {
    if (logFile == INVALID_HANDLE_VALUE) return;
    wchar_t text[PATH_CAP + 512];
    SYSTEMTIME now;
    GetLocalTime(&now);
    int used = _snwprintf(text, 64, L"%04u-%02u-%02u %02u:%02u:%02u ", now.wYear, now.wMonth, now.wDay, now.wHour,
                          now.wMinute, now.wSecond);
    if (used < 0) used = 0;
    va_list args;
    va_start(args, format);
    _vsnwprintf(text + used, sizeof text / sizeof text[0] - used - 3, format, args);
    va_end(args);
    text[sizeof text / sizeof text[0] - 3] = 0;
    wcscat(text, L"\r\n");
    char bytes[(PATH_CAP + 512) * 3];
    int length = WideCharToMultiByte(CP_UTF8, 0, text, -1, bytes, sizeof bytes, NULL, NULL);
    DWORD written;
    if (length > 1) WriteFile(logFile, bytes, (DWORD)(length - 1), &written, NULL);
}

/* ---- paths ----------------------------------------------------------------------------------- */

static void join(wchar_t *out, const wchar_t *folder, const wchar_t *name) {
    _snwprintf(out, PATH_CAP - 1, L"%ls\\%ls", folder, name);
    out[PATH_CAP - 1] = 0;
}

static int exists(const wchar_t *path) { return GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES; }

static int is_file(const wchar_t *path) {
    DWORD attributes = GetFileAttributesW(path);
    return attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_DIRECTORY);
}

/* A full path without a trailing backslash; 0 when Windows can't make one. */
static int full_path(const wchar_t *in, wchar_t *out) {
    DWORD got = GetFullPathNameW(in, PATH_CAP, out, NULL);
    if (got == 0 || got >= PATH_CAP) return 0;
    size_t length = wcslen(out);
    while (length > 3 && out[length - 1] == L'\\') out[--length] = 0;
    return 1;
}

/* Deletes a folder and everything in it; a junction or link is removed, never followed. */
static void delete_tree(const wchar_t *path) {
    DWORD attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES) return;
    if (!(attributes & FILE_ATTRIBUTE_DIRECTORY) || (attributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
        SetFileAttributesW(path, FILE_ATTRIBUTE_NORMAL);
        if (attributes & FILE_ATTRIBUTE_DIRECTORY) RemoveDirectoryW(path); else DeleteFileW(path);
        return;
    }
    wchar_t *pattern = malloc(PATH_CAP * sizeof(wchar_t));
    wchar_t *child = malloc(PATH_CAP * sizeof(wchar_t));
    if (!pattern || !child) {
        free(pattern);
        free(child);
        return;
    }
    join(pattern, path, L"*");
    WIN32_FIND_DATAW found;
    HANDLE search = FindFirstFileW(pattern, &found);
    if (search != INVALID_HANDLE_VALUE) {
        do {
            if (wcscmp(found.cFileName, L".") == 0 || wcscmp(found.cFileName, L"..") == 0) continue;
            join(child, path, found.cFileName);
            delete_tree(child);
        } while (FindNextFileW(search, &found));
        FindClose(search);
    }
    free(pattern);
    free(child);
    SetFileAttributesW(path, FILE_ATTRIBUTE_NORMAL);
    RemoveDirectoryW(path);
}

/*
 * A rename, tried for up to ten seconds: just after the app exits, Windows (or a virus scanner
 * looking at the new files) can still hold a file for a moment, which shows as a sharing violation
 * or access denied, and passes.
 */
static DWORD move(const wchar_t *from, const wchar_t *to) {
    DWORD error = 0;
    for (int attempt = 0; attempt < 40; attempt++) {
        if (MoveFileExW(from, to, MOVEFILE_WRITE_THROUGH)) return 0;
        error = GetLastError();
        if (error != ERROR_SHARING_VIOLATION && error != ERROR_ACCESS_DENIED && error != ERROR_LOCK_VIOLATION) break;
        Sleep(250);
    }
    return error ? error : 1;
}

/* ---- window ---------------------------------------------------------------------------------- */

static void set_status(const wchar_t *text) {
    log_line(L"%ls", text);
    if (window) SendMessageW(window, WM_APP_STATUS, 0, (LPARAM)text);
}

static void message(const wchar_t *text, UINT flags) {
    log_line(L"message: %ls", text);
    MessageBoxW(window, text, L"BitChord", flags | MB_SETFOREGROUND);
}

static int scaled(int value) {
    HDC screen = GetDC(NULL);
    int dpi = GetDeviceCaps(screen, LOGPIXELSY);
    ReleaseDC(NULL, screen);
    return MulDiv(value, dpi ? dpi : 96, 96);
}

static LRESULT CALLBACK window_proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_CREATE: {
        HINSTANCE instance = ((CREATESTRUCTW *)lParam)->hInstance;
        int pad = scaled(20), width = scaled(380) - 2 * pad;
        heading = CreateWindowExW(0, L"STATIC", L"Updating BitChord…", WS_CHILD | WS_VISIBLE | SS_LEFT, pad, scaled(18),
                                  width, scaled(26), hwnd, NULL, instance, NULL);
        status = CreateWindowExW(0, L"STATIC", L"Waiting for BitChord to close…", WS_CHILD | WS_VISIBLE | SS_LEFT | SS_ENDELLIPSIS,
                                 pad, scaled(48), width, scaled(20), hwnd, NULL, instance, NULL);
        progress = CreateWindowExW(0, PROGRESS_CLASSW, NULL, WS_CHILD | WS_VISIBLE | PBS_MARQUEE, pad, scaled(78), width,
                                   scaled(6), hwnd, NULL, instance, NULL);
        SendMessageW(progress, PBM_SETMARQUEE, TRUE, 30);
        SendMessageW(heading, WM_SETFONT, (WPARAM)headingFont, TRUE);
        SendMessageW(status, WM_SETFONT, (WPARAM)bodyFont, TRUE);
        return 0;
    }
    case WM_CTLCOLORSTATIC:
        SetBkColor((HDC)wParam, GetSysColor(COLOR_WINDOW));
        SetTextColor((HDC)wParam, GetSysColor(COLOR_WINDOWTEXT));
        return (LRESULT)background;
    case WM_APP_STATUS:
        SetWindowTextW(status, (const wchar_t *)lParam);
        return 0;
    case WM_APP_DONE:
        DestroyWindow(hwnd);
        return 0;
    case WM_CLOSE:
        /* Not while files are being moved: closing then would leave nothing worse, but nothing better. */
        if (finished) DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

/* ---- the update ------------------------------------------------------------------------------ */

/* Starts <app>\BitChord.exe; 0 on success, else the Windows error. */
static DWORD start_app(void) {
    wchar_t exe[PATH_CAP], command[PATH_CAP + 64];
    join(exe, appDir, L"BitChord.exe");
    _snwprintf(command, sizeof command / sizeof command[0] - 1, L"\"%ls\"%ls", exe, logMode ? L" --log" : L"");
    command[sizeof command / sizeof command[0] - 1] = 0;
    STARTUPINFOW startup;
    ZeroMemory(&startup, sizeof startup);
    startup.cb = sizeof startup;
    PROCESS_INFORMATION process;
    ZeroMemory(&process, sizeof process);
    AllowSetForegroundWindow(ASFW_ANY);
    if (!CreateProcessW(exe, command, NULL, NULL, FALSE, 0, NULL, appDir, &startup, &process)) {
        DWORD error = GetLastError();
        log_line(L"starting %ls failed: Windows error %lu", exe, (unsigned long)error);
        return error ? error : 1;
    }
    log_line(L"started %ls (pid %lu)", exe, (unsigned long)process.dwProcessId);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return 0;
}

/*
 * Whether [process] started when the app said it did (--started): within two seconds, as Java and
 * Windows may round the same creation time differently. True when there is nothing to compare.
 */
static int started_as_given(HANDLE process) {
    if (!appStarted) return 1;
    FILETIME created, exited, kernel, user;
    if (!GetProcessTimes(process, &created, &exited, &kernel, &user)) return 1;
    ULARGE_INTEGER ticks;
    ticks.LowPart = created.dwLowDateTime;
    ticks.HighPart = created.dwHighDateTime;
    ULONGLONG ms = (ticks.QuadPart - 116444736000000000ULL) / 10000ULL; /* 100 ns since 1601 to ms since 1970 */
    ULONGLONG apart = ms > appStarted ? ms - appStarted : appStarted - ms;
    return apart <= 2000;
}

static int movedOut[ITEM_COUNT], movedIn[ITEM_COUNT];

/* Puts back what was moved; 1 when everything went back. */
static int roll_back(void) {
    int clean = 1;
    wchar_t from[PATH_CAP], to[PATH_CAP];
    for (int i = (int)ITEM_COUNT - 1; i >= 0; i--) {
        if (movedIn[i]) {
            join(from, appDir, ITEMS[i]);
            join(to, stagedDir, ITEMS[i]);
            DWORD error = move(from, to);
            log_line(L"roll back: %ls to staged: %ls", ITEMS[i], error ? L"FAILED" : L"ok");
            if (error) clean = 0; else movedIn[i] = 0;
        }
        if (movedOut[i] && !movedIn[i]) {
            join(from, oldDir, ITEMS[i]);
            join(to, appDir, ITEMS[i]);
            DWORD error = move(from, to);
            log_line(L"roll back: %ls from old: %ls", ITEMS[i], error ? L"FAILED" : L"ok");
            if (error) clean = 0; else movedOut[i] = 0;
        }
    }
    return clean;
}

static void fail_and_restore(const wchar_t *what, DWORD error) {
    set_status(L"Putting the old version back…");
    int clean = roll_back();
    wchar_t text[PATH_CAP + 512];
    if (clean) {
        _snwprintf(text, sizeof text / sizeof text[0] - 1,
                   L"The update couldn't be installed (%ls, Windows error %lu). BitChord was left as it was and opens again.",
                   what, (unsigned long)error);
    } else {
        _snwprintf(text, sizeof text / sizeof text[0] - 1,
                   L"The update couldn't be installed (%ls, Windows error %lu), and not every file could be put back. "
                   L"The old program files are in:\n\n%ls\n\nMove them back into the BitChord folder, or unzip the release again "
                   L"over it. Your data folder was not touched.",
                   what, (unsigned long)error, oldDir);
    }
    text[sizeof text / sizeof text[0] - 1] = 0;
    message(text, MB_OK | (clean ? MB_ICONWARNING : MB_ICONERROR));
    if (clean) start_app();
}

static DWORD WINAPI update(LPVOID unused) {
    (void)unused;
    /* 1. Wait for the app to be gone. */
    HANDLE app = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, appPid);
    if (app && !started_as_given(app)) {
        log_line(L"pid %lu belongs to another program now (BitChord has exited)", (unsigned long)appPid);
        CloseHandle(app);
        app = NULL;
        SetLastError(0);
    }
    if (app) {
        for (;;) {
            DWORD waited = WaitForSingleObject(app, WAIT_FOR_EXIT_MS);
            if (waited != WAIT_TIMEOUT) break;
            log_line(L"BitChord (pid %lu) is still running after %d s", (unsigned long)appPid, WAIT_FOR_EXIT_MS / 1000);
            int answer = MessageBoxW(window,
                                     L"BitChord hasn't closed after two minutes, so the update is waiting.\n\n"
                                     L"Quit it (tray icon > Quit BitChord) and press Retry, or press Cancel to keep the current version.",
                                     L"BitChord", MB_RETRYCANCEL | MB_ICONWARNING | MB_SETFOREGROUND);
            if (answer != IDRETRY) {
                log_line(L"cancelled while waiting; nothing was changed");
                CloseHandle(app);
                return 0;
            }
        }
        CloseHandle(app);
    } else {
        log_line(L"pid %lu is not running (Windows error %lu)", (unsigned long)appPid, (unsigned long)GetLastError());
    }

    /* 2. Is what we were given an update at all? Nothing is moved otherwise. */
    set_status(L"Checking the new version…");
    static const wchar_t *const required[] = {L"BitChord.exe", L"lib\\bitchord-app.jar", L"runtime\\bin\\javaw.exe"};
    wchar_t path[PATH_CAP], to[PATH_CAP];
    for (size_t i = 0; i < sizeof required / sizeof required[0]; i++) {
        join(path, stagedDir, required[i]);
        if (!is_file(path)) {
            wchar_t text[PATH_CAP + 128];
            _snwprintf(text, sizeof text / sizeof text[0] - 1, L"The downloaded update is incomplete (%ls is missing). Nothing was changed.",
                       required[i]);
            text[sizeof text / sizeof text[0] - 1] = 0;
            message(text, MB_OK | MB_ICONWARNING);
            start_app();
            return 0;
        }
    }

    /* 3. The old program files out of the way, into data\update\old. */
    set_status(L"Replacing BitChord's program files…");
    delete_tree(oldDir); /* a leftover from an update whose new app never confirmed its start */
    if (!CreateDirectoryW(oldDir, NULL) && GetLastError() != ERROR_ALREADY_EXISTS) {
        DWORD error = GetLastError();
        fail_and_restore(L"making data\\update\\old", error);
        return 0;
    }
    for (size_t i = 0; i < ITEM_COUNT; i++) {
        join(path, appDir, ITEMS[i]);
        if (!exists(path)) continue;
        join(to, oldDir, ITEMS[i]);
        DWORD error = move(path, to);
        log_line(L"old %ls to data\\update\\old: %ls", ITEMS[i], error ? L"FAILED" : L"ok");
        if (error) {
            fail_and_restore(ITEMS[i], error);
            return 0;
        }
        movedOut[i] = 1;
    }

    /* 4. The new ones in. */
    for (size_t i = 0; i < ITEM_COUNT; i++) {
        join(path, stagedDir, ITEMS[i]);
        if (!exists(path)) continue;
        join(to, appDir, ITEMS[i]);
        DWORD error = move(path, to);
        log_line(L"new %ls into the app folder: %ls", ITEMS[i], error ? L"FAILED" : L"ok");
        if (error) {
            fail_and_restore(ITEMS[i], error);
            return 0;
        }
        movedIn[i] = 1;
    }

    /* 5. The new app. If it can't even be started, the old one comes back. */
    set_status(L"Starting BitChord…");
    DWORD error = start_app();
    if (error) {
        fail_and_restore(L"starting the new BitChord.exe", error);
        return 0;
    }
    log_line(L"done");
    Sleep(800);
    return 0;
}

static DWORD WINAPI worker(LPVOID unused) {
    update(unused);
    InterlockedExchange(&finished, 1);
    PostMessageW(window, WM_APP_DONE, 0, 0);
    return 0;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR commandLine, int show) {
    (void)previous;
    (void)commandLine;
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv || argc < 4 || !full_path(argv[1], appDir) || !full_path(argv[2], stagedDir)) {
        MessageBoxW(NULL, L"BitChordUpdate.exe is started by BitChord itself, not by hand.", L"BitChord", MB_OK | MB_ICONINFORMATION);
        return 2;
    }
    appPid = (DWORD)wcstoul(argv[3], NULL, 10);
    for (int i = 4; i < argc; i++) {
        if (wcscmp(argv[i], L"--log") == 0) logMode = 1;
        else if (wcsncmp(argv[i], L"--started=", 10) == 0) appStarted = _wcstoui64(argv[i] + 10, NULL, 10);
    }
    LocalFree(argv);

    join(updateDir, appDir, L"data\\update");
    join(oldDir, updateDir, L"old");
    wchar_t logPath[PATH_CAP];
    join(logPath, updateDir, L"update.log");
    CreateDirectoryW(updateDir, NULL);
    /* This update's log only: appended to, it grew by one run per update for good. */
    logFile = CreateFileW(logPath, GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    log_line(L"update: app %ls, staged %ls, pid %lu, started %llu%ls", appDir, stagedDir, (unsigned long)appPid, appStarted,
             logMode ? L", --log" : L"");

    /*
     * The staged folder must be inside data\update\ of this app folder: the only place the app
     * stages to, and a guarantee that the moves below never reach anything else.
     */
    size_t prefix = wcslen(updateDir);
    wchar_t appExe[PATH_CAP];
    join(appExe, appDir, L"BitChord.exe");
    if (_wcsnicmp(stagedDir, updateDir, prefix) != 0 || stagedDir[prefix] != L'\\' || !is_file(appExe)) {
        log_line(L"refused: the staged folder is not inside data\\update, or the app folder has no BitChord.exe");
        MessageBoxW(NULL, L"BitChordUpdate.exe was started with folders it doesn't recognise. Nothing was changed.", L"BitChord",
                    MB_OK | MB_ICONWARNING);
        return 2;
    }

    INITCOMMONCONTROLSEX controls = {sizeof controls, ICC_PROGRESS_CLASS};
    InitCommonControlsEx(&controls);
    NONCLIENTMETRICSW metrics;
    ZeroMemory(&metrics, sizeof metrics);
    metrics.cbSize = sizeof metrics;
    SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof metrics, &metrics, 0);
    bodyFont = CreateFontIndirectW(&metrics.lfMessageFont);
    LOGFONTW large = metrics.lfMessageFont;
    large.lfHeight = MulDiv(large.lfHeight, 4, 3);
    large.lfWeight = FW_SEMIBOLD;
    headingFont = CreateFontIndirectW(&large);
    background = GetSysColorBrush(COLOR_WINDOW);

    WNDCLASSEXW type;
    ZeroMemory(&type, sizeof type);
    type.cbSize = sizeof type;
    type.lpfnWndProc = window_proc;
    type.hInstance = instance;
    type.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(1));
    type.hIconSm = type.hIcon;
    type.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    type.hbrBackground = background;
    type.lpszClassName = L"BitChordUpdate";
    RegisterClassExW(&type);

    DWORD style = WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    RECT frame = {0, 0, scaled(380), scaled(106)};
    AdjustWindowRect(&frame, style, FALSE);
    RECT work;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
    int width = frame.right - frame.left, height = frame.bottom - frame.top;
    window = CreateWindowExW(0, type.lpszClassName, L"BitChord", style, work.left + (work.right - work.left - width) / 2,
                             work.top + (work.bottom - work.top - height) / 2, width, height, NULL, NULL, instance, NULL);
    ShowWindow(window, show == SW_HIDE ? SW_SHOWNORMAL : show);
    SetForegroundWindow(window);

    HANDLE thread = CreateThread(NULL, 0, worker, NULL, 0, NULL);
    if (!thread) {
        log_line(L"could not start the worker thread");
        return 1;
    }
    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    WaitForSingleObject(thread, INFINITE);
    CloseHandle(thread);
    if (logFile != INVALID_HANDLE_VALUE) CloseHandle(logFile);
    return 0;
}
