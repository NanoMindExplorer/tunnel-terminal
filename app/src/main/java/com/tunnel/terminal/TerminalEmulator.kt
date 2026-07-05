package com.tunnel.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Representasi satu sel terminal dengan karakter, warna foreground, dan style.
 * Terminal cell with char, fg color, bg color, and text style attributes.
 */
data class TerminalCell(
    var char: Char = ' ',
    var fgColor: Color = Color(0xFF00FF00),
    var bgColor: Color = Color.Black,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var reverse: Boolean = false
)

/**
 * ThemeHolder - holder sederhana untuk tema aktif yang bisa di-share
 * antar TerminalEmulator instance tanpa re-create emulator saat ganti tema.
 *
 * Simple holder for active theme shared across emulator instances.
 */
class ThemeHolder(var theme: TerminalTheme = ThemeManager.defaultTheme)

/**
 * TerminalEmulator - Emulator terminal lengkap dengan screen buffer.
 *
 * Phase 17 (Major Bug Fix) - Penambahan signifikan:
 * - Alternate screen buffer (1049h/l) untuk vim/nano/less
 * - Cursor visibility (?25h/l) untuk vim/htop
 * - 256-color (38;5;n) dan TrueColor (38;2;r;g;b)
 * - SGR attributes: bold (1), italic (3), underline (4), reverse (7)
 * - K parameter 0/1/2 (clear line variants)
 * - J parameter 0/1/2/3 (clear screen variants)
 * - Scrolling region (Cs r)
 * - Save/restore cursor (s/u, 7/8)
 * - Erase in line/screen dengan benar
 * - OSC sequence diabaikan dengan benar (tidak print garbage)
 * - Private mode (?...) parsing yang benar
 */
class TerminalEmulator(private val themeHolder: ThemeHolder = ThemeHolder()) {
    var rows: Int = 24
        private set
    var cols: Int = 80
        private set
    var fontSize: TextUnit = 12.sp
        private set

    /** Screen buffer utama. Main screen buffer. */
    private var screen = Array(rows) { Array(cols) { blankCell() } }

    /**
     * Phase 49 fix (E-1): Scrollback buffer — simpan baris yang keluar dari layar.
     *
     * OLD BUG: Baris yang scroll keluar dari screen hilang permanen. User tidak bisa
     * scroll ke atas untuk lihat output sebelumnya (mis. apt install dengan banyak log).
     *
     * FIX: Ring buffer yang simpan maksimal SCROLLBACK_MAX baris terakhir yang keluar.
     * Saat scrollUp() dipanggil, baris teratas (scrollTop) di-push ke scrollback.
     * User bisa akses via getScrollbackLines() untuk render di scroll view.
     */
    private val scrollbackMaxLines = 2000
    private val scrollbackLines = ArrayDeque<Array<TerminalCell>>()
    /** Jumlah baris yang user scroll ke atas (0 = di bottom/live). */
    private var scrollbackOffset = 0
    /** Alternate screen buffer (untuk TUI apps). Alt screen buffer for TUI. */
    private var altScreen: Array<Array<TerminalCell>>? = null

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set

    /** Cursor visibility (vim/htop sembunyikan cursor). */
    var isCursorVisible = true
        private set

    /** Saved cursor position (untuk DECSC/DECRC). */
    private var savedCursorRow = 0
    private var savedCursorCol = 0

    /** Current style attributes. */
    private var currentFg: Color = themeHolder.theme.foreground
    private var currentBg: Color = themeHolder.theme.background
    private var currentBold = false
    private var currentItalic = false
    private var currentUnderline = false
    private var currentReverse = false

    /** Default colors (reset target) - read from theme. */
    private val defaultFg: Color get() = themeHolder.theme.foreground
    private val defaultBg: Color get() = themeHolder.theme.background

    /** BUG-08 fix: Helper untuk membuat cell kosong dengan warna tema aktif. */
    private fun blankCell() = TerminalCell(fgColor = defaultFg, bgColor = defaultBg)

    /** Scrolling region (top, bottom inclusive, 0-indexed). */
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    /** BUG-09 fix: Callback untuk menulis respons balik ke PTY (DA/DSR). */
    var writeCallback: ((String) -> Unit)? = null

    /** True jika sedang di alt screen. */
    private var inAltScreen = false

    /**
     * Regex untuk ANSI escape sequences:
     * 1. OSC sequences: \u001B]...\u0007 (atau \u001B\\)
     * 2. CSI sequences: \u001B[paramsfinal
     * 3. Charset designation: \u001B()*+char
     * 4. Single ESC + char: ESC = > 7 8 c H M D E (yang umum)
     */
    private val ansiRegex = Regex(
        "\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +  // OSC
        "|\u001B\\[[;?0-9]*[A-Za-z]" +                       // CSI
        "|\u001B[()*+][A-Za-z0-9]" +                         // Charset designation
        "|\u001B[=>78cHMDE]"                                // Misc single escapes
    )

    fun resize(newRows: Int, newCols: Int, newFontSize: TextUnit) {
        synchronized(lock) {
            if (newRows <= 0 || newCols <= 0) return
            if (newRows == rows && newCols == cols && newFontSize == fontSize) return

            /* Phase 48 fix (F-2): Resize KETIGA buffer (screen + altScreen + mainScreen)
             * sekaligus dalam satu synchronized block.
             *
             * Phase 54 fix: Saat mengecil (keyboard muncul), pertahankan baris PALING
             * BAWAH (tempat kursor & konten aktif), buang dari ATAS (histori lama).
             * Sebelumnya kebalik: buang bagian bawah yang justru sedang dipakai user. */

            /* FIX: hitung offset SEBELUM rows berubah, dipakai untuk geser kursor. */
            val rowOffset = if (newRows < rows) rows - newRows else 0

            screen = resizeBuffer(screen, newRows, newCols)
            altScreen = altScreen?.let { resizeBuffer(it, newRows, newCols) }
            mainScreen = mainScreen?.let { resizeBuffer(it, newRows, newCols) }

            rows = newRows
            cols = newCols
            fontSize = newFontSize
            /* BUG-07 fix: Reset scroll region ke layar penuh saat resize. */
            scrollTop = 0
            scrollBottom = rows - 1

            /* FIX: geser kursor dengan offset yang sama, BUKAN cuma clamp ke rows-1.
             * Clamp lama menaruh kursor di baris yang salah — isinya bukan
             * lanjutan dari yang sedang diketik. */
            cursorRow = (cursorRow - rowOffset).coerceIn(0, rows - 1)
            if (cursorCol >= cols) cursorCol = cols - 1

            /* FIX sekunder: savedCursorRow juga perlu digeser — kalau tidak, keluar
             * dari vim/TUI app setelah resize mengecil akan mengembalikan kursor
             * ke posisi yang sudah tidak sesuai dengan mainScreen yang baru. */
            if (rowOffset != 0) {
                savedCursorRow = (savedCursorRow - rowOffset).coerceIn(0, rows - 1)
            }
        }
    }

    /**
     * Phase 48 fix (F-2): Resize buffer dengan pad/crop.
     * Phase 54 fix: Saat mengecil, pertahankan baris PALING BAWAH (baris terbaru),
     * buang dari ATAS (histori terlama). Sebelumnya kebalik.
     */
    private fun resizeBuffer(buf: Array<Array<TerminalCell>>, newRows: Int, newCols: Int): Array<Array<TerminalCell>> {
        val newBuf = Array(newRows) { Array(newCols) { blankCell() } }
        if (newRows >= buf.size) {
            /* Membesar (atau sama): baris lama tetap di posisi yang sama,
             * baris baru yang kosong ditambah di bawah. Perilaku lama, tidak berubah. */
            for (r in buf.indices) {
                for (c in 0 until minOf(buf[r].size, newCols)) {
                    newBuf[r][c] = buf[r][c]
                }
            }
        } else {
            /* FIX: Mengecil — simpan newRows baris PALING BAWAH (baris terbaru,
             * tempat kursor & konten yang sedang aktif), buang dari ATAS
             * (histori terlama). Sebelumnya kebalik: buang bagian yang justru
             * lagi dipakai user, simpan histori lama yang sudah tidak relevan. */
            val rowOffset = buf.size - newRows
            for (r in 0 until newRows) {
                val srcRow = r + rowOffset
                for (c in 0 until minOf(buf[srcRow].size, newCols)) {
                    newBuf[r][c] = buf[srcRow][c]
                }
            }
        }
        return newBuf
    }

    /**
     * Phase 21: Thread safety lock. process() dipanggil dari readLoop (background),
     * getScreen()/cursorRow/cursorCol dibaca dari Compose (main thread).
     * Tanpa sync, Compose bisa baca screen array saat sedang di-mutate -> crash/corruption.
     *
     * Thread safety lock for process() vs getScreen()/cursor reads.
     */
    private val lock = Any()

    /**
     * Phase 21: Snapshot screen untuk Compose rendering.
     * Returns a COPY of screen array agar Compose tidak baca array yang sedang di-mutate.
     * Copy dibuat di dalam synchronized block untuk konsistensi.
     *
     * Returns a snapshot copy of screen array for thread-safe Compose rendering.
     */
    fun getScreenSnapshot(): Array<Array<TerminalCell>> {
        synchronized(lock) {
            /* Shallow copy baris, lalu copy setiap baris (array of TerminalCell).
             * TerminalCell adalah data class — copy sekali per cell cukup (char + colors + flags).
             * Untuk performance, bisa di-cache, tapi untuk sekarang copy langsung. */
            return Array(rows) { r ->
                Array(cols) { c ->
                    screen[r][c].copy()  /* data class copy */
                }
            }
        }
    }

    /** Legacy alias — sekarang returns snapshot copy. */
    fun getScreen(): Array<Array<TerminalCell>> = getScreenSnapshot()

    /** Phase 21: Snapshot cursor position untuk thread-safe reads. */
    data class CursorState(val row: Int, val col: Int, val visible: Boolean)
    fun getCursorState(): CursorState {
        synchronized(lock) {
            return CursorState(cursorRow, cursorCol, isCursorVisible)
        }
    }

    /** Phase 26: Thread-safe rows/cols reads (avoid ArrayIndexOutOfBounds during resize).
     * Named snapshotRows/snapshotCols to avoid JVM signature clash with var rows getter. */
    fun snapshotRows(): Int = synchronized(lock) { rows }
    fun snapshotCols(): Int = synchronized(lock) { cols }

    /**
     * Phase 48 fix (F-1): Atomic render state — gabungkan screen+cursor+rows+cols
     * dalam SATU synchronized block untuk konsistensi.
     *
     * OLD BUG: Compose membaca render state lewat 4 pemanggilan terpisah:
     *   getScreenSnapshot()  — di-remember(screenDirty)
     *   getCursorState()     — di-remember(screenDirty)
     *   snapshotRows()       — TIDAK di-remember → nilai paling baru
     *   snapshotCols()       — TIDAK di-remember → nilai paling baru
     * Saat resize terjadi, renderRows/renderCols bisa jadi nilai baru sementara
     * screenSnapshot masih array ukuran lama → index-out-of-range atau misalignment
     * → "layar menghilang/bergeser".
     *
     * FIX: getRenderState() ambil keempatnya dalam satu transaksi atomik.
     * Compose cukup satu panggilan: remember(screenDirty) { emulator.getRenderState() }.
     */
    data class RenderState(
        val screen: Array<Array<TerminalCell>>,
        val cursor: CursorState,
        val rows: Int,
        val cols: Int
    )

    fun getRenderState(): RenderState = synchronized(lock) {
        RenderState(
            screen = Array(rows) { r ->
                Array(cols) { c ->
                    screen[r][c].copy()
                }
            },
            cursor = CursorState(cursorRow, cursorCol, isCursorVisible),
            rows = rows,
            cols = cols
        )
    }

    /**
     * Phase 49 fix (E-1): Scrollback access methods.
     * User scroll ke atas → tampilkan baris dari scrollback buffer.
     */

    /** Jumlah baris scrollback yang tersimpan. */
    fun getScrollbackCount(): Int = synchronized(lock) { scrollbackLines.size }

    /** Ambil baris scrollback untuk ditampilkan saat user scroll ke atas.
     *  @param offset 0 = baris paling baru di scrollback (terakhir keluar dari layar) */
    fun getScrollbackLine(offset: Int): Array<TerminalCell>? = synchronized(lock) {
        if (offset < 0 || offset >= scrollbackLines.size) return null
        scrollbackLines[scrollbackLines.size - 1 - offset]?.map { it.copy() }?.toTypedArray()
    }

    /** Ambil multiple baris scrollback untuk render batch.
     *  @param startOffset offset mulai (0 = terbaru)
     *  @param count jumlah baris yang diminta */
    fun getScrollbackLines(startOffset: Int, count: Int): List<Array<TerminalCell>> = synchronized(lock) {
        val result = mutableListOf<Array<TerminalCell>>()
        for (i in 0 until count) {
            val offset = startOffset + i
            if (offset < 0 || offset >= scrollbackLines.size) break
            val line = scrollbackLines[scrollbackLines.size - 1 - offset]
            result.add(line.map { it.copy() }.toTypedArray())
        }
        result
    }

    /** Clear scrollback (mis. saat user ketik 'clear'). */
    fun clearScrollback() = synchronized(lock) {
        scrollbackLines.clear()
        scrollbackOffset = 0
    }

    fun setCursor(row: Int, col: Int) {
        synchronized(lock) {
            cursorRow = row.coerceIn(0, rows - 1)
            cursorCol = col.coerceIn(0, cols - 1)
        }
    }

    fun setCursorVisible(visible: Boolean) {
        synchronized(lock) {
            isCursorVisible = visible
        }
    }

    /**
     * Buffer untuk partial ANSI sequence yang ter-split antar process() call.
     * Saat streaming/chunked reads, escape sequence bisa terpotong di tengah
     * (e.g., "\u001B[3" lalu next chunk "3m"). Buffer ini simpan sisa yang
     * belum complete, flush di process() berikutnya.
     *
     * Buffer for partial ANSI sequences split across process() calls.
     */
    private val pendingBuffer = StringBuilder()

    /**
     * Phase 21: Synchronized process() — hanya satu thread yang bisa mutate screen.
     * readLoop memanggil ini, Compose membaca via getScreenSnapshot().
     */
    fun process(data: String) = synchronized(lock) {
        processInternal(data)
    }

    /** Flush pending buffer — juga synchronized. */
    fun flush() = synchronized(lock) {
        if (pendingBuffer.isNotEmpty()) {
            val data = pendingBuffer.toString()
            pendingBuffer.setLength(0)
            printText(data)
        }
    }

    /**
     * Phase 44 fix (MED-01): Recolor semua sel yang masih pakai warna default tema lama
     * ke warna default tema baru. Dipanggil saat user ganti tema.
     *
     * OLD BUG: Sel yang ditulis dengan defaultFg/defaultBg (sebagian besar sel biasa)
     * menyimpan referensi warna dari tema saat sel itu ditulis. Ganti tema hanya
     * mengubah defaultFg/defaultBg untuk sel BARU — sel lama tetap warna lama →
     * layar "belang" sampai user ketik `clear`.
     *
     * FIX: Iterasi seluruh screen + altScreen. Untuk sel yang fgColor/bgColor-nya
     * sama dengan warna default tema LAMA, remap ke warna default tema BARU.
     * Sel yang sudah di-override lewat SGR eksplisit (mis. merah untuk error)
     * tidak diubah — preserve user intent.
     *
     * Catatan: Ini best-effort. Kalau warna default lama kebetulan sama dengan
     * warna SGR eksplisit, sel itu akan ikut di-remap (false positive). Tapi
     * untuk use case umum (tema gelap→terang atau sebaliknya), ini cukup baik.
     */
    fun recolorForTheme(oldFg: Color, oldBg: Color) = synchronized(lock) {
        val newFg = defaultFg
        val newBg = defaultBg
        for (row in screen.indices) {
            for (col in screen[row].indices) {
                val cell = screen[row][col]
                var changed = false
                if (cell.fgColor == oldFg) { cell.fgColor = newFg; changed = true }
                if (cell.bgColor == oldBg) { cell.bgColor = newBg; changed = true }
                /* Jika cell reverse video, swap fg/bg juga ikut default — remap. */
                if (cell.reverse) {
                    if (cell.fgColor == oldBg) cell.fgColor = newBg
                    if (cell.bgColor == oldFg) cell.bgColor = newFg
                }
                if (changed) screen[row][col] = cell
            }
        }
        /* Apply ke altScreen juga. */
        altScreen?.let { alt ->
            for (row in alt.indices) {
                for (col in alt[row].indices) {
                    val cell = alt[row][col]
                    if (cell.fgColor == oldFg) cell.fgColor = newFg
                    if (cell.bgColor == oldBg) cell.bgColor = newBg
                    if (cell.reverse) {
                        if (cell.fgColor == oldBg) cell.fgColor = newBg
                        if (cell.bgColor == oldFg) cell.bgColor = newFg
                    }
                }
            }
        }
    }

    private fun processInternal(data: String) {
        /* Prepend pending buffer dari process() sebelumnya.
         * Prepend pending buffer from previous process() call. */
        val fullData = if (pendingBuffer.isNotEmpty()) {
            val combined = pendingBuffer.toString() + data
            pendingBuffer.setLength(0)
            combined
        } else {
            data
        }

        /* Cari last complete escape sequence. Sisa setelah itu mungkin partial.
         * Find last complete escape; remainder may be partial. */
        var lastCompleteEnd = 0
        ansiRegex.findAll(fullData).forEach { match ->
            /* Hanya consider match complete jika tidak ada ESC setelahnya yang belum ter-match. */
            lastCompleteEnd = match.range.last + 1
        }

        /* Cek apakah ada ESC (\u001B) di sisa data setelah lastCompleteEnd.
         * Jika ya, simpan sisa ke pendingBuffer (mulai dari ESC tersebut). */
        val remaining = fullData.substring(lastCompleteEnd)
        val escIndex = remaining.indexOf('\u001B')

        val processNow: String
        val pending: String
        if (escIndex >= 0) {
            /* Ada ESC di remaining — split di sana.
             * Process text sebelum ESC (yang pasti complete),
             * simpan dari ESC onwards ke pending. */
            val completeText = remaining.substring(0, escIndex)
            processNow = fullData.substring(0, lastCompleteEnd) + completeText
            pending = remaining.substring(escIndex)
        } else {
            /* Tidak ada ESC di remaining — semua complete. */
            processNow = fullData
            pending = ""
        }

        if (pending.isNotEmpty()) {
            /* Batasi pending buffer agar tidak bengkak (max 64 bytes). */
            if (pending.length <= 64) {
                pendingBuffer.append(pending)
            }
            /* Jika > 64 bytes, kemungkinan bukan escape valid, buang saja. */
        }

        /* Process yang pasti complete. */
        if (processNow.isEmpty()) return

        var lastIndex = 0
        ansiRegex.findAll(processNow).forEach { match ->
            val textBefore = processNow.substring(lastIndex, match.range.first)
            if (textBefore.isNotEmpty()) printText(textBefore)
            handleEscape(match.value)
            lastIndex = match.range.last + 1
        }
        val remainingText = processNow.substring(lastIndex)
        if (remainingText.isNotEmpty()) printText(remainingText)
    }

    /* flush() moved to synchronized version above (Phase 21 thread safety). */

    private fun printText(text: String) {
        for (ch in text.toCharArray()) {
            when (ch) {
                '\n' -> { cursorCol = 0; cursorRow++ }
                '\r' -> cursorCol = 0
                '\b' -> if (cursorCol > 0) cursorCol--
                '\t' -> {
                    val next = ((cursorCol / 8) + 1) * 8
                    cursorCol = if (next >= cols) { cursorRow++; 0 } else next
                }
                '\u0007' -> { /* bell - ignore */ }
                else -> {
                    if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
                        val cell = screen[cursorRow][cursorCol]
                        cell.char = ch
                        cell.fgColor = if (currentReverse) currentBg else currentFg
                        cell.bgColor = if (currentReverse) currentFg else currentBg
                        cell.bold = currentBold
                        cell.italic = currentItalic
                        cell.underline = currentUnderline
                    }
                    cursorCol++
                    if (cursorCol >= cols) { cursorCol = 0; cursorRow++ }
                }
            }
            if (cursorRow > scrollBottom) scrollUp(cursorRow - scrollBottom)
        }
    }

    /** Scroll up sebanyak n baris dalam scrolling region.
     *  Phase 49 fix (E-1): Baris yang keluar dari scrollTop disimpan ke scrollback. */
    private fun scrollUp(n: Int) {
        if (n <= 0) return
        val effective = minOf(n, scrollBottom - scrollTop + 1)
        for (i in 0 until effective) {
            /* Phase 49 (E-1): Simpan baris teratas ke scrollback sebelum di-overwrite. */
            if (altScreen == null) {  // hanya simpan ke scrollback di main screen, bukan alt
                val topRow = screen[scrollTop].copyOf()  // copy baris (array of TerminalCell)
                scrollbackLines.addLast(topRow)
                while (scrollbackLines.size > scrollbackMaxLines) {
                    scrollbackLines.removeFirst()
                }
            }
            for (r in scrollTop until scrollBottom) {
                screen[r] = screen[r + 1]
            }
            screen[scrollBottom] = Array(cols) { blankCell() }
        }
        cursorRow = scrollBottom
        /* Reset scrollback offset — user di bottom lagi (new output). */
        scrollbackOffset = 0
    }

    /** Scroll down sebanyak n baris dalam scrolling region (untuk RI). */
    private fun scrollDown(n: Int) {
        if (n <= 0) return
        val effective = minOf(n, scrollBottom - scrollTop + 1)
        for (i in 0 until effective) {
            for (r in scrollBottom downTo scrollTop + 1) {
                screen[r] = screen[r - 1]
            }
            screen[scrollTop] = Array(cols) { blankCell() }
        }
        cursorRow = scrollTop
    }

    private fun handleEscape(seq: String) {
        when {
            /* OSC sequence - set window title, dll. Abaikan. */
            seq.startsWith("\u001B]") -> return

            /* CSI sequence */
            seq.startsWith("\u001B[") -> {
                val body = seq.substring(2, seq.length - 1)
                val finalChar = seq.last()
                /* Pisahkan private marker (?) dari parameter. */
                val isPrivate = body.startsWith("?")
                val params = if (isPrivate) body.substring(1) else body
                handleCsi(params, finalChar, isPrivate)
            }

            /* ESC c - full reset */
            seq == "\u001Bc" -> fullReset()

            /* ESC 7 / ESC M - save cursor */
            seq == "\u001B7" || seq == "\u001B M" -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
            }

            /* ESC 8 - restore cursor */
            seq == "\u001B8" -> {
                cursorRow = savedCursorRow
                cursorCol = savedCursorCol
            }

            /* ESC H - set tab stop at current column (ignore, we use fixed 8-width) */
            seq == "\u001BH" -> return

            /* ESC D - IND (Index): line feed, scroll jika perlu.
             * ESC D - IND (Index): line feed, scroll if needed. */
            seq == "\u001BD" -> {
                cursorRow++
                if (cursorRow > scrollBottom) scrollUp(1)
            }

            /* ESC M - RI (Reverse Index): naik satu baris, scroll down jika di top.
             * ESC M - RI (Reverse Index): move up, scroll down if at top. */
            seq == "\u001BM" -> {
                if (cursorRow <= scrollTop) scrollDown(1) else cursorRow--
            }

            /* ESC E - NEL (Next Line): CR + LF */
            seq == "\u001BE" -> {
                cursorCol = 0
                cursorRow++
                if (cursorRow > scrollBottom) scrollUp(1)
            }

            /* ESC = / > - keypad mode (ignore) */
            seq == "\u001B=" || seq == "\u001B>" -> return

            /* Charset designation - ignore */
            seq.startsWith("\u001B(") || seq.startsWith("\u001B)") ||
            seq.startsWith("\u001B*") || seq.startsWith("\u001B+") -> return
        }
    }

    private fun handleCsi(params: String, command: Char, isPrivate: Boolean) {
        val paramList = if (params.isEmpty()) emptyList()
                        else params.split(";").mapNotNull { it.toIntOrNull() ?: 0 }

        when (command) {
            /* SGR - Set Graphic Rendition (warna & style) */
            'm' -> handleSgr(paramList)

            /* Cursor positioning */
            'H', 'f' -> {
                val r = paramList.getOrElse(0) { 1 } - 1
                val c = paramList.getOrElse(1) { 1 } - 1
                cursorRow = r.coerceIn(0, rows - 1)
                cursorCol = c.coerceIn(0, cols - 1)
            }

            /* Cursor movement */
            'A' -> cursorRow = (cursorRow - paramList.getOrElse(0) { 1 }).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + paramList.getOrElse(0) { 1 }).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + paramList.getOrElse(0) { 1 }).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - paramList.getOrElse(0) { 1 }).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + paramList.getOrElse(0) { 1 }).coerceAtMost(rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - paramList.getOrElse(0) { 1 }).coerceAtLeast(0); cursorCol = 0 }
            'G' -> cursorCol = (paramList.getOrElse(0) { 1 } - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (paramList.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
            's' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol }
            'u' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol }

            /* Erase in display (J) */
            'J' -> {
                val mode = paramList.getOrElse(0) { 0 }
                when (mode) {
                    0 -> { /* cursor to end of screen */
                        for (c in cursorCol until cols) screen[cursorRow][c] = blankCell()
                        for (r in cursorRow + 1 until rows) {
                            for (c in 0 until cols) screen[r][c] = blankCell()
                        }
                    }
                    1 -> { /* start of screen to cursor */
                        for (r in 0 until cursorRow) {
                            for (c in 0 until cols) screen[r][c] = blankCell()
                        }
                        for (c in 0..cursorCol) screen[cursorRow][c] = blankCell()
                    }
                    2, 3 -> { /* entire screen + scrollback (3) */
                        for (r in 0 until rows) {
                            for (c in 0 until cols) screen[r][c] = blankCell()
                        }
                        cursorRow = 0; cursorCol = 0
                    }
                }
            }

            /* Erase in line (K) */
            'K' -> {
                val mode = paramList.getOrElse(0) { 0 }
                if (cursorRow < rows) {
                    when (mode) {
                        0 -> for (c in cursorCol until cols) screen[cursorRow][c] = blankCell()
                        1 -> for (c in 0..cursorCol) screen[cursorRow][c] = blankCell()
                        2 -> for (c in 0 until cols) screen[cursorRow][c] = blankCell()
                    }
                }
            }

            /* Insert/delete lines */
            'L' -> insertLines(paramList.getOrElse(0) { 1 })
            'M' -> deleteLines(paramList.getOrElse(0) { 1 })

            /* Delete characters */
            'P' -> deleteChars(paramList.getOrElse(0) { 1 })
            /* Erase characters (fill with space, don't shift) */
            'X' -> eraseChars(paramList.getOrElse(0) { 1 })
            /* Insert characters */
            '@' -> insertChars(paramList.getOrElse(0) { 1 })

            /* Set scrolling region */
            'r' -> {
                val top = (paramList.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
                val bot = (paramList.getOrElse(1) { rows } - 1).coerceIn(0, rows - 1)
                if (top < bot) {
                    scrollTop = top
                    scrollBottom = bot
                    cursorRow = 0
                    cursorCol = 0
                }
            }

            /* Private mode set/reset (? h / ? l) */
            'h' -> if (isPrivate) handlePrivateMode(paramList, set = true)
            'l' -> if (isPrivate) handlePrivateMode(paramList, set = false)

            /* IND (Index) dan RI (Reverse Index) adalah ESC D / ESC M (tanpa [),
             * ditangani di handleEscape(). Jangan duplikat di sini.
             * IND/RI are ESC D/M without [; handled in handleEscape(). */

            /* BUG-09 fix: Respond to DA (Device Attributes) dan DSR (Device Status Report). */
            'c' -> {
                /* CSI c atau CSI 0 c — Device Attributes. Respond as VT102. */
                writeCallback?.invoke("\u001B[?1;2c")
            }
            'n' -> {
                /* CSI 6 n — Device Status Report (cursor position). */
                if (paramList.isNotEmpty() && paramList[0] == 6) {
                    writeCallback?.invoke("\u001B[${cursorRow + 1};${cursorCol + 1}R")
                }
                /* CSI 5 n — Device Status. Respond "OK". */
                else if (paramList.isNotEmpty() && paramList[0] == 5) {
                    writeCallback?.invoke("\u001B[0n")
                }
            }
            else -> { /* unhandled CSI - ignore */ }
        }
    }

    private fun handleSgr(params: List<Int>) {
        if (params.isEmpty()) {
            resetStyle()
            return
        }
        val palette = themeHolder.theme.ansi
        var i = 0
        while (i < params.size) {
            val code = params[i]
            when (code) {
                0 -> resetStyle()
                1 -> currentBold = true
                2 -> currentBold = false /* dim - approximated */
                3 -> currentItalic = true
                4 -> currentUnderline = true
                7 -> currentReverse = true
                21 -> currentUnderline = false /* double underline -> off */
                22 -> currentBold = false
                23 -> currentItalic = false
                24 -> currentUnderline = false
                27 -> currentReverse = false

                /* Standard foreground 30-37 (theme palette 0-7). */
                30 -> currentFg = palette.getOrElse(0) { Color.Black }
                31 -> currentFg = palette.getOrElse(1) { Color(0xFFFF5252) }
                32 -> currentFg = palette.getOrElse(2) { Color(0xFF4CAF50) }
                33 -> currentFg = palette.getOrElse(3) { Color(0xFFFFC107) }
                34 -> currentFg = palette.getOrElse(4) { Color(0xFF2196F3) }
                35 -> currentFg = palette.getOrElse(5) { Color(0xFFE040FB) }
                36 -> currentFg = palette.getOrElse(6) { Color(0xFF00BCD4) }
                37 -> currentFg = palette.getOrElse(7) { Color.White }

                /* Bright foreground 90-97 (theme palette 8-15). */
                90 -> currentFg = palette.getOrElse(8) { Color(0xFF757575) }
                91 -> currentFg = palette.getOrElse(9) { Color(0xFFFF8A80) }
                92 -> currentFg = palette.getOrElse(10) { Color(0xFFB9F6CA) }
                93 -> currentFg = palette.getOrElse(11) { Color(0xFFFFF59D) }
                94 -> currentFg = palette.getOrElse(12) { Color(0xFF82B1FF) }
                95 -> currentFg = palette.getOrElse(13) { Color(0xFFEA80FC) }
                96 -> currentFg = palette.getOrElse(14) { Color(0xFF84FFFF) }
                97 -> currentFg = palette.getOrElse(15) { Color.White }

                /* Standard background 40-47 (theme palette 0-7). */
                40 -> currentBg = palette.getOrElse(0) { Color.Black }
                41 -> currentBg = palette.getOrElse(1) { Color(0xFFFF5252) }
                42 -> currentBg = palette.getOrElse(2) { Color(0xFF4CAF50) }
                43 -> currentBg = palette.getOrElse(3) { Color(0xFFFFC107) }
                44 -> currentBg = palette.getOrElse(4) { Color(0xFF2196F3) }
                45 -> currentBg = palette.getOrElse(5) { Color(0xFFE040FB) }
                46 -> currentBg = palette.getOrElse(6) { Color(0xFF00BCD4) }
                47 -> currentBg = palette.getOrElse(7) { Color.White }

                /* Bright background 100-107 (theme palette 8-15). */
                100 -> currentBg = palette.getOrElse(8) { Color(0xFF757575) }
                101 -> currentBg = palette.getOrElse(9) { Color(0xFFFF8A80) }
                102 -> currentBg = palette.getOrElse(10) { Color(0xFFB9F6CA) }
                103 -> currentBg = palette.getOrElse(11) { Color(0xFFFFF59D) }
                104 -> currentBg = palette.getOrElse(12) { Color(0xFF82B1FF) }
                105 -> currentBg = palette.getOrElse(13) { Color(0xFFEA80FC) }
                106 -> currentBg = palette.getOrElse(14) { Color(0xFF84FFFF) }
                107 -> currentBg = palette.getOrElse(15) { Color.White }

                /* 38 - extended foreground color */
                38 -> {
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> { /* 256-color: 38;5;n */
                                if (i + 2 < params.size) {
                                    currentFg = color256(params[i + 2])
                                    i += 2
                                }
                            }
                            2 -> { /* TrueColor: 38;2;r;g;b */
                                if (i + 4 < params.size) {
                                    currentFg = Color(
                                        params[i + 2].coerceIn(0, 255),
                                        params[i + 3].coerceIn(0, 255),
                                        params[i + 4].coerceIn(0, 255)
                                    )
                                    i += 4
                                }
                            }
                        }
                    }
                }

                /* 48 - extended background color */
                48 -> {
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> {
                                if (i + 2 < params.size) {
                                    currentBg = color256(params[i + 2])
                                    i += 2
                                }
                            }
                            2 -> {
                                if (i + 4 < params.size) {
                                    currentBg = Color(
                                        params[i + 2].coerceIn(0, 255),
                                        params[i + 3].coerceIn(0, 255),
                                        params[i + 4].coerceIn(0, 255)
                                    )
                                    i += 4
                                }
                            }
                        }
                    }
                }

                /* 39 - default foreground */
                39 -> currentFg = defaultFg
                /* 49 - default background */
                49 -> currentBg = defaultBg
            }
            i++
        }
    }

    /** Map kode 256-color ke Color. Pakai palette tema untuk 0-15.
     * Map 256-color code to Color. Uses theme palette for 0-15. */
    private fun color256(n: Int): Color {
        val idx = n.coerceIn(0, 255)
        /* 0-15: pakai palette dari tema aktif.
         * Use theme palette for 0-15. */
        if (idx < 16) {
            return themeHolder.theme.ansi.getOrElse(idx) { Color.White }
        }
        /* 16-231: 6x6x6 color cube (warna tetap, tidak tema-dependent). */
        if (idx < 232) {
            val off = idx - 16
            val r = off / 36
            val g = (off / 6) % 6
            val b = off % 6
            val lvl = intArrayOf(0, 95, 135, 175, 215, 255)
            return Color(lvl[r], lvl[g], lvl[b])
        }
        /* 232-255: grayscale */
        val gray = 8 + (idx - 232) * 10
        return Color(gray, gray, gray)
    }

    private fun resetStyle() {
        currentFg = defaultFg
        currentBg = defaultBg
        currentBold = false
        currentItalic = false
        currentUnderline = false
        currentReverse = false
    }

    /**
     * Main screen buffer (saved saat entering alt screen, restored saat exiting).
     * Phase 20: Fix alt screen restore losing main screen content.
     */
    private var mainScreen: Array<Array<TerminalCell>>? = null

    private fun handlePrivateMode(params: List<Int>, set: Boolean) {
        params.forEach { mode ->
            when (mode) {
                /* Alternate screen buffer */
                1049, 1047, 1048 -> {
                    if (set) {
                        /* Phase 20: Save main screen BEFORE switching to alt.
                         * Old code overwrote screen ref without saving -> main content lost. */
                        if (!inAltScreen) {
                            mainScreen = screen
                        }
                        if (altScreen == null) altScreen = Array(rows) { Array(cols) { blankCell() } }
                        /* Clear alt screen. */
                        for (r in 0 until rows) {
                            for (c in 0 until cols) altScreen!![r][c] = blankCell()
                        }
                        inAltScreen = true
                        screen = altScreen!!
                        cursorRow = 0; cursorCol = 0
                    } else {
                        /* Phase 20: Restore main screen (was creating blank screen). */
                        inAltScreen = false
                        screen = mainScreen ?: Array(rows) { Array(cols) { blankCell() } }
                        mainScreen = null
                        /* Note: cursor position not restored (1048 handles that separately).
                         * 1049 = save cursor + switch; 1047 = switch only; 1048 = save cursor only.
                         * Simplifikasi: reset cursor on exit. */
                        cursorRow = 0; cursorCol = 0
                    }
                }
                /* Cursor visibility */
                25 -> isCursorVisible = set
                /* Auto-wrap (7) - kita selalu wrap, abaikan */
                7, /* Application cursor keys */
                1, /* Bracketed paste */
                2004, /* Reverse video */
                5, /* Origin mode */
                6 -> { /* ignore - simplifikasi */ }
                else -> { /* unhandled private mode */ }
            }
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val effective = minOf(n, scrollBottom - cursorRow + 1)
        for (i in 0 until effective) {
            for (r in scrollBottom downTo cursorRow + 1) {
                screen[r] = screen[r - 1]
            }
            screen[cursorRow] = Array(cols) { blankCell() }
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val effective = minOf(n, scrollBottom - cursorRow + 1)
        for (i in 0 until effective) {
            for (r in cursorRow until scrollBottom) {
                screen[r] = screen[r + 1]
            }
            screen[scrollBottom] = Array(cols) { blankCell() }
        }
    }

    private fun deleteChars(n: Int) {
        if (cursorRow >= rows) return
        val effective = minOf(n, cols - cursorCol)
        for (i in 0 until effective) {
            for (c in cursorCol until cols - 1) {
                screen[cursorRow][c] = screen[cursorRow][c + 1]
            }
            screen[cursorRow][cols - 1] = blankCell()
        }
    }

    private fun eraseChars(n: Int) {
        if (cursorRow >= rows) return
        val end = minOf(cursorCol + n, cols)
        for (c in cursorCol until end) screen[cursorRow][c] = blankCell()
    }

    private fun insertChars(n: Int) {
        if (cursorRow >= rows) return
        val effective = minOf(n, cols - cursorCol)
        for (i in 0 until effective) {
            for (c in cols - 1 downTo cursorCol + 1) {
                screen[cursorRow][c] = screen[cursorRow][c - 1]
            }
            screen[cursorRow][cursorCol] = blankCell()
        }
    }

    private fun fullReset() {
        resetStyle()
        if (inAltScreen) {
            inAltScreen = false
            altScreen = null
            screen = Array(rows) { Array(cols) { blankCell() } }
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) screen[r][c] = blankCell()
        }
        cursorRow = 0; cursorCol = 0
        scrollTop = 0; scrollBottom = rows - 1
        isCursorVisible = true
    }
}
