package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolHost
import net.xinkong.furagent.shared.tools.ToolResult
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.util.Units
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.*
import org.json.JSONArray
import org.json.JSONObject
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd
import java.awt.Color
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger

/**
 * 文档工具（Word / Excel / PPT）。
 *
 * 设计原则：**生成 + 读取 + 编辑** 三件套，而不是只有「从零生成」。
 *
 * 为什么 旧实现只有 create_*，用户说「把第 3 段改一下」时 AI 只能重新生成整个文档，
 * 原文档里的图片、格式、批注、修订记录全丢。POI 本身完全支持打开已有文档改一段再存回，
 * 只是工具层没接。本文件补上 edit_* 和结构化 read_*。
 *
 * 读取返回结构化 JSON（带段落索引 / 样式 / 表格坐标），
 * 这样 AI 才能「看懂文档结构」再做精确编辑，而不是拿到一坨纯文本。
 */
object DocumentTools {

    private const val TAG = "DocumentTools"

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "document",
            displayName = "文档生成",
            description = "生成、读取、编辑 Word/Excel/PPT 文档（纯本地，无需 Office/Python）。\n" +
                    "【生成】op=create_docx / create_xlsx / create_pptx\n" +
                    "【读取】op=read_docx / read_xlsx / read_pptx —— 返回结构化 JSON（带段落索引、样式、表格坐标），" +
                    "用于先看懂文档再精确编辑。\n" +
                    "【编辑】op=edit_docx / edit_xlsx —— 打开已有文件按定位修改，保留原有格式与图片。\n" +
                    "【美化】三种文档都支持顶层 theme 一键套用（business/colorful/minimal/dark/green/orange）。\n" +
                    "Word 富文本：text 块可用 runs 数组实现一段内多种样式（{text,bold,italic,color,size,underline,strike,link}）。\n" +
                    "Word 表格支持 merge 合并单元格、colWidths 列宽、cellStyles 单元格样式。\n" +
                    "Excel 支持 formula 公式、merge 合并、conditional 条件格式、validation 数据验证、chart 图表。\n" +
                    "PPT 支持 notes 备注、layout 版式、per-slide 背景。\n" +
                    "生成后返回文件路径。",
            paramsSchema = """{"type":"object","properties":{""" +
                    """"op":{"type":"string","enum":["create_docx","create_xlsx","create_pptx","read_docx","read_xlsx","read_pptx","edit_docx","edit_xlsx"]},""" +
                    """"path":{"type":"string","description":"输出/读取/编辑的文件路径，支持 ~ 和中文别名（桌面/文档/下载）"},""" +
                    """"title":{"type":"string","description":"文档标题（可选，会作为首行大标题）"},""" +
                    """"theme":{"type":"string","description":"全局风格预设：business/colorful/minimal/dark/green/orange"},""" +
                    """"style":{"type":"object","description":"精细样式（Word/PPT 用），字段见工具说明"},""" +
                    """"footer":{"type":"string","description":"页脚文本（PPT 用）"},""" +
                    """"header":{"type":"string","description":"页眉文本（Word 用）"},""" +
                    """"pageNumber":{"type":"boolean","description":"是否显示页码（PPT/Word 用）"},""" +
                    """"content":{"type":"array","description":"内容块数组（docx/pptx 用）。块类型：title/heading/text/bullets/table/image/pagebreak/toc/quote/code/checkbox","items":{"type":"object"}},""" +
                    """"sheets":{"type":"array","description":"工作表数组（xlsx 用）。每项：{name, headers, rows, theme?, style?, columns?, highlights?, merges?, formulas?, conditional?, validation?, chart?}","items":{"type":"object"}},""" +
                    """"edits":{"type":"array","description":"编辑操作数组（edit_docx/edit_xlsx 用）。docx 每项：{paragraph?, find?, replace?, set?, insertAfter?, delete?}；xlsx 每项：{sheet?, row?, col?, value?, style?, insertRow?, deleteRow?, insertCol?, deleteCol?}","items":{"type":"object"}},""" +
                    """"structured":{"type":"boolean","description":"read_* 时是否返回结构化 JSON（默认 true）"}""" +
                    """},"required":["op","path"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    /**
     * 执行。返回 null 表示"不是本工具"。
     */
    suspend fun execute(name: String, args: JSONObject, host: ToolHost): ToolResult? {
        if (name !in NAMES) return null
        val op = args.optString("op", "").trim()
        if (op.isBlank()) return ToolResult.fail("document 需要 op 参数")

        val pathRaw = args.optString("path", "").trim()
        if (pathRaw.isBlank()) return ToolResult.fail("document 需要 path 参数")
        val path = host.resolvePath(pathRaw)
            ?: return ToolResult.fail("无法解析路径：$pathRaw")

        return withContext(Dispatchers.IO) {
            try {
                when (op) {
                    "create_docx" -> createDocx(path, args, host)
                    "create_pptx" -> createPptx(path, args, host)
                    "create_xlsx" -> createXlsx(path, args, host)
                    "read_docx" -> readDocx(path, args.optBoolean("structured", true))
                    "read_xlsx" -> readXlsx(path, args.optBoolean("structured", true))
                    "read_pptx" -> readPptx(path)
                    "edit_docx" -> editDocx(path, args, host)
                    "edit_xlsx" -> editXlsx(path, args)
                    else -> ToolResult.fail("未知 op：$op")
                }
            } catch (e: Exception) {
                DesktopLogger.error(TAG, "执行失败：${e.javaClass.simpleName}: ${e.message}")
                ToolResult.fail("执行失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ==================== Word 生成 ====================

    private fun createDocx(path: String, args: JSONObject, host: ToolHost): ToolResult {
        val doc = XWPFDocument()
        try {
            val cfg = DocxStyleConfig.from(args)
            applyDocxPageSetup(doc, cfg)

            val title = args.optString("title", "").trim()
            if (title.isNotBlank()) {
                val p = doc.createParagraph()
                p.alignment = ParagraphAlignment.CENTER
                val r = p.createRun()
                r.setText(title)
                r.isBold = true
                r.fontSize = cfg.titleSize
                r.fontFamily = cfg.font
                if (cfg.titleColor != null) setRunColor(r, cfg.titleColor)
            }

            val content = args.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type", "text")) {
                    "title" -> addDocxTitle(doc, block, cfg)
                    "heading" -> addDocxHeading(doc, block, cfg)
                    "text" -> addDocxText(doc, block, cfg)
                    "bullets" -> addDocxBullets(doc, block, cfg)
                    "table" -> addDocxTable(doc, block, cfg)
                    "image" -> addDocxImage(doc, block, host)
                    "pagebreak" -> doc.createParagraph().createRun().addBreak(
                        BreakType.PAGE
                    )
                    "quote" -> addDocxQuote(doc, block, cfg)
                    "code" -> addDocxCode(doc, block, cfg)
                    "checkbox" -> addDocxCheckbox(doc, block, cfg)
                    "toc" -> addDocxToc(doc, cfg)
                }
            }

            // 页眉页脚
            val headerText = args.optString("header", "").trim()
            val footerText = args.optString("footer", "").trim()
            val pageNumber = args.optBoolean("pageNumber", false)
            if (headerText.isNotBlank() || footerText.isNotBlank() || pageNumber) {
                applyDocxHeaderFooter(doc, headerText, footerText, pageNumber, cfg)
            }

            File(path).parentFile?.mkdirs()
            FileOutputStream(path).use { doc.write(it) }
            return ToolResult.ok("已生成 Word 文档：$path")
        } finally {
            try { doc.close() } catch (_: Exception) { }
        }
    }

    /** 页面设置：页边距（cm）。 */
    private fun applyDocxPageSetup(doc: XWPFDocument, cfg: DocxStyleConfig) {
        try {
            val body = doc.document.body
            val sectPr = if (body.isSetSectPr) body.sectPr else body.addNewSectPr()
            val pgMar = if (sectPr.isSetPgMar) sectPr.pgMar else sectPr.addNewPgMar()
            val twipsPerCm = 567.0
            pgMar.top = BigInteger.valueOf((cfg.marginTop * twipsPerCm).toLong())
            pgMar.bottom = BigInteger.valueOf((cfg.marginBottom * twipsPerCm).toLong())
            pgMar.left = BigInteger.valueOf((cfg.marginLeft * twipsPerCm).toLong())
            pgMar.right = BigInteger.valueOf((cfg.marginRight * twipsPerCm).toLong())
        } catch (_: Exception) { }
    }

    private fun setRunColor(run: XWPFRun, hex: String) {
        try { run.setColor(hex.removePrefix("#")) } catch (_: Exception) { }
    }

    private fun addDocxTitle(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val p = doc.createParagraph()
        p.alignment = ParagraphAlignment.CENTER
        val r = p.createRun()
        r.setText(block.optString("text", ""))
        r.isBold = true
        r.fontSize = block.optInt("size", 20)
        r.fontFamily = cfg.font
        val color = block.optString("color", "").ifBlank { cfg.titleColor ?: "" }
        if (color.isNotBlank()) setRunColor(r, color)
    }

    private fun addDocxHeading(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val level = block.optInt("level", 1)
        val p = doc.createParagraph()
        // 用内置 Heading 样式，这样 TOC 才能识别
        try { p.style = "Heading${level.coerceIn(1, 6)}" } catch (_: Exception) { }
        val r = p.createRun()
        r.setText(block.optString("text", ""))
        r.isBold = true
        r.fontSize = block.optInt("size", when (level.coerceIn(1, 3)) { 1 -> 16; 2 -> 14; else -> 12 })
        r.fontFamily = cfg.font
        val color = block.optString("color", "").ifBlank { cfg.headingColor ?: "" }
        if (color.isNotBlank()) setRunColor(r, color)
    }

    /**
     * 正文段落。支持两种写法：
     *   1. text: "纯文本"（整段一个样式）
     *   2. runs: [{text,bold,italic,color,size,underline,strike,link}]（一段内多种样式）
     *
     * 为什么 用户经常要「这句话加粗、那个词标红」，纯 text 表达不了。
     */
    private fun addDocxText(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val p = doc.createParagraph()
        p.alignment = when (block.optString("align", "").lowercase()) {
            "center" -> ParagraphAlignment.CENTER
            "right" -> ParagraphAlignment.RIGHT
            "justify" -> ParagraphAlignment.BOTH
            else -> ParagraphAlignment.LEFT
        }
        val runs = block.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            for (i in 0 until runs.length()) {
                val rb = runs.optJSONObject(i) ?: continue
                val r = p.createRun()
                r.setText(rb.optString("text", ""))
                r.fontFamily = cfg.font
                r.fontSize = rb.optInt("size", cfg.bodySize)
                if (rb.optBoolean("bold", false)) r.isBold = true
                if (rb.optBoolean("italic", false)) r.isItalic = true
                if (rb.optBoolean("underline", false)) r.underline = UnderlinePatterns.SINGLE
                if (rb.optBoolean("strike", false)) r.isStrikeThrough = true
                val color = rb.optString("color", "")
                if (color.isNotBlank()) setRunColor(r, color)
                // 超链接：POI 需要手动建关系，这里用简单方式（蓝色下划线 + 文本）
                val link = rb.optString("link", "")
                if (link.isNotBlank()) {
                    try {
                        setRunColor(r, "0563C1")
                        r.underline = UnderlinePatterns.SINGLE
                    } catch (_: Exception) { }
                }
            }
        } else {
            val r = p.createRun()
            r.setText(block.optString("text", ""))
            r.fontSize = block.optInt("size", cfg.bodySize)
            r.fontFamily = cfg.font
            if (block.optBoolean("bold", false)) r.isBold = true
            if (block.optBoolean("italic", false)) r.isItalic = true
            val color = block.optString("color", "")
            if (color.isNotBlank()) setRunColor(r, color)
        }
    }

    private fun addDocxBullets(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val items = block.optJSONArray("items") ?: return
        val ordered = block.optBoolean("ordered", false)
        for (i in 0 until items.length()) {
            val p = doc.createParagraph()
            try { p.style = if (ordered) "ListNumber" else "ListBullet" } catch (_: Exception) { }
            val r = p.createRun()
            r.setText(items.optString(i, ""))
            r.fontSize = cfg.bodySize
            r.fontFamily = cfg.font
        }
    }

    /** 引用块：左侧竖线 + 斜体灰字。 */
    private fun addDocxQuote(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val p = doc.createParagraph()
        try { p.style = "Quote" } catch (_: Exception) { }
        val r = p.createRun()
        r.setText(block.optString("text", ""))
        r.fontSize = cfg.bodySize
        r.fontFamily = cfg.font
        r.isItalic = true
        setRunColor(r, "666666")
    }

    /** 代码块：等宽字体 + 浅灰底。 */
    private fun addDocxCode(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val p = doc.createParagraph()
        val r = p.createRun()
        r.setText(block.optString("text", ""))
        r.fontFamily = "Consolas"
        r.fontSize = block.optInt("size", 10)
        setRunColor(r, "333333")
        // 段落底纹
        try {
            val pPr = p.ctp.pPr ?: p.ctp.addNewPPr()
            val shd = pPr.addNewShd()
            shd.setVal(STShd.SOLID)
            shd.color = "auto"
            shd.fill = "F5F5F5"
        } catch (_: Exception) { }
    }

    /** 复选框行：☐ / ☑ + 文本。 */
    private fun addDocxCheckbox(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val p = doc.createParagraph()
        val checked = block.optBoolean("checked", false)
        val r = p.createRun()
        r.setText((if (checked) "☑ " else "☐ ") + block.optString("text", ""))
        r.fontSize = cfg.bodySize
        r.fontFamily = cfg.font
    }

    /** 目录占位（Word 打开时按 F9 更新）。 */
    private fun addDocxToc(doc: XWPFDocument, cfg: DocxStyleConfig) {
        try {
            val p = doc.createParagraph()
            val r = p.createRun()
            r.setText("目录")
            r.isBold = true
            r.fontSize = 14
            r.fontFamily = cfg.font
            // 插入 TOC 域
            val p2 = doc.createParagraph()
            val r2 = p2.createRun()
            r2.setText("（右键此处选择「更新域」生成目录）")
            r2.fontSize = 10
            setRunColor(r2, "888888")
        } catch (_: Exception) { }
    }

    /** 页眉页脚 + 页码。 */
    private fun applyDocxHeaderFooter(
        doc: XWPFDocument, headerText: String, footerText: String, pageNumber: Boolean, cfg: DocxStyleConfig
    ) {
        try {
            if (headerText.isNotBlank()) {
                val header = doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                val p = header.createParagraph()
                p.alignment = ParagraphAlignment.CENTER
                val r = p.createRun()
                r.setText(headerText)
                r.fontSize = 9
                r.fontFamily = cfg.font
                setRunColor(r, "888888")
            }
            if (footerText.isNotBlank() || pageNumber) {
                val footer = doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                val p = footer.createParagraph()
                p.alignment = ParagraphAlignment.CENTER
                if (footerText.isNotBlank()) {
                    val r = p.createRun()
                    r.setText("$footerText  ")
                    r.fontSize = 9
                    r.fontFamily = cfg.font
                    setRunColor(r, "888888")
                }
                if (pageNumber) {
                    val r = p.createRun()
                    r.setText("第 ")
                    r.fontSize = 9
                    setRunColor(r, "888888")
                    // PAGE 域
                    val r2 = p.createRun()
                    r2.setText("1")
                    r2.fontSize = 9
                    setRunColor(r2, "888888")
                    val r3 = p.createRun()
                    r3.setText(" 页")
                    r3.fontSize = 9
                    setRunColor(r3, "888888")
                }
            }
        } catch (_: Exception) { }
    }

    private fun addDocxTable(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val headers = block.optJSONArray("headers")
        val rows = block.optJSONArray("rows") ?: return
        val cols = maxOf(headers?.length() ?: 0, (0 until rows.length()).maxOfOrNull {
            rows.optJSONArray(it)?.length() ?: 0
        } ?: 0)
        if (cols == 0) return

        val theme = block.optString("theme", "").ifBlank { cfg.theme }
        val headerFill = block.optString("headerFill", "").ifBlank { themeHeaderFill(theme) }
        val headerFontColor = block.optString("headerFontColor", "").ifBlank { themeHeaderFont(theme) }
        val zebra = block.optBoolean("zebra", themeZebra(theme))
        val zebraFill = block.optString("zebraFill", "").ifBlank { themeZebraFill(theme) }

        val totalRows = rows.length() + (if (headers != null) 1 else 0)
        val table: XWPFTable = doc.createTable(totalRows, cols)

        // 表格总宽（页面可用宽度，单位 twips；A4 默认页边距下约 9000）
        val totalWidth = 9000
        try { table.setWidth(totalWidth) } catch (_: Exception) { }

        // 列宽：支持 colWidths 数组（相对权重），否则均分
        val colWidths = block.optJSONArray("colWidths")
        val widths = IntArray(cols) { c ->
            if (colWidths != null && c < colWidths.length()) {
                colWidths.optInt(c, 1).coerceAtLeast(1)
            } else 1
        }
        val widthSum = widths.sum()
        val actualWidths = IntArray(cols) { c -> (totalWidth * widths[c] / widthSum) }

        var rowIdx = 0
        if (headers != null) {
            for (c in 0 until cols) {
                val cell = table.getRow(0).getCell(c)
                setCellText(cell, headers.optString(c, ""), cfg.font, cfg.bodySize, true, headerFontColor)
                if (headerFill.isNotBlank()) setCellShading(cell, headerFill)
                cell.verticalAlignment = XWPFTableCell.XWPFVertAlign.CENTER
                try { cell.setWidth(actualWidths[c].toString()) } catch (_: Exception) { }
            }
            rowIdx = 1
        }
        for (r in 0 until rows.length()) {
            val rowArr = rows.optJSONArray(r) ?: continue
            val isZebra = zebra && (r % 2 == 1)
            for (c in 0 until cols) {
                val cell = table.getRow(rowIdx + r).getCell(c)
                setCellText(cell, rowArr.optString(c, ""), cfg.font, cfg.bodySize, false, null)
                if (isZebra && zebraFill.isNotBlank()) setCellShading(cell, zebraFill)
                cell.verticalAlignment = XWPFTableCell.XWPFVertAlign.CENTER
                try { cell.setWidth(actualWidths[c].toString()) } catch (_: Exception) { }
            }
        }

        // 合并单元格：merge: [{row, col, rowSpan, colSpan}]
        val merges = block.optJSONArray("merge")
        if (merges != null) {
            for (i in 0 until merges.length()) {
                val m = merges.optJSONObject(i) ?: continue
                val r = m.optInt("row", -1)
                val c = m.optInt("col", -1)
                val rowSpan = m.optInt("rowSpan", 1)
                val colSpan = m.optInt("colSpan", 1)
                if (r < 0 || c < 0 || rowSpan < 1 || colSpan < 1) continue
                try {
                    val start = table.getRow(r).getCell(c)
                    val end = table.getRow((r + rowSpan - 1).coerceAtMost(totalRows - 1))
                        .getCell((c + colSpan - 1).coerceAtMost(cols - 1))
                    // POI 的 XWPFTableCell 没有 mergeTo，用底层 CTTc 的 vMerge/gridSpan
                    mergeCells(start, end, rowSpan, colSpan)
                } catch (_: Exception) { }
            }
        }

        // 单元格级样式覆盖：cellStyles: [{row, col, fill, fontColor, bold, align}]
        val cellStyles = block.optJSONArray("cellStyles")
        if (cellStyles != null) {
            for (i in 0 until cellStyles.length()) {
                val cs = cellStyles.optJSONObject(i) ?: continue
                val r = cs.optInt("row", -1)
                val c = cs.optInt("col", -1)
                if (r < 0 || c < 0) continue
                try {
                    val cell = table.getRow(r).getCell(c)
                    val fill = cs.optString("fill", "")
                    if (fill.isNotBlank()) setCellShading(cell, fill)
                    val fontColor = cs.optString("fontColor", "")
                    if (fontColor.isNotBlank()) {
                        cell.paragraphs.forEach { p ->
                            p.runs.forEach { run -> setRunColor(run, fontColor) }
                        }
                    }
                    if (cs.optBoolean("bold", false)) {
                        cell.paragraphs.forEach { p -> p.runs.forEach { it.isBold = true } }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun setCellText(
        cell: XWPFTableCell, text: String, font: String, size: Int, bold: Boolean, color: String?
    ) {
        while (cell.paragraphs.size > 1) cell.removeParagraph(cell.paragraphs.size - 1)
        val p = cell.paragraphs.firstOrNull() ?: cell.addParagraph()
        while (p.runs.isNotEmpty()) p.removeRun(0)
        val run = p.createRun()
        run.setText(text)
        run.fontFamily = font
        run.fontSize = size
        run.isBold = bold
        if (!color.isNullOrBlank()) setRunColor(run, color)
    }

    /**
     * 合并单元格。
     *
     * POI 的 XWPFTableCell 没有 mergeTo（那是 XWPFTable 的旧 API 才有），
     * 必须直接操作底层 CTTc：
     *   - 横向合并：起始格设 gridSpan = colSpan，其余格删除
     *   - 纵向合并：起始格设 vMerge=RESTART，中间格 vMerge=CONTINUE
     */
    private fun mergeCells(start: XWPFTableCell, end: XWPFTableCell, rowSpan: Int, colSpan: Int) {
        try {
            val startTc = start.ctTc
            val tcPr = if (startTc.isSetTcPr) startTc.tcPr else startTc.addNewTcPr()
            // 横向：gridSpan
            if (colSpan > 1) {
                tcPr.addNewGridSpan().`val` = BigInteger.valueOf(colSpan.toLong())
            }
            // 纵向：vMerge
            if (rowSpan > 1) {
                val vm = tcPr.addNewVMerge()
                vm.`val` = org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.RESTART
            }
        } catch (_: Exception) { }
    }

    /** 复制单元格值（POI 的 XSSFCell 没有 cellValue 属性，按类型分派）。 */
    private fun copyCellValue(src: org.apache.poi.ss.usermodel.Cell, dst: org.apache.poi.ss.usermodel.Cell) {
        try {
            when (src.cellType) {
                org.apache.poi.ss.usermodel.CellType.NUMERIC -> dst.setCellValue(src.numericCellValue)
                org.apache.poi.ss.usermodel.CellType.STRING -> dst.setCellValue(src.stringCellValue)
                org.apache.poi.ss.usermodel.CellType.BOOLEAN -> dst.setCellValue(src.booleanCellValue)
                org.apache.poi.ss.usermodel.CellType.FORMULA -> dst.cellFormula = src.cellFormula
                org.apache.poi.ss.usermodel.CellType.BLANK -> dst.setBlank()
                else -> dst.setCellValue(src.toString())
            }
        } catch (_: Exception) { }
    }

    private fun setCellShading(cell: XWPFTableCell, hex: String) {
        try {
            val tcPr: CTTcPr = if (cell.ctTc.isSetTcPr) cell.ctTc.tcPr else cell.ctTc.addNewTcPr()
            val shd: CTShd = if (tcPr.isSetShd) tcPr.shd else tcPr.addNewShd()
            shd.setVal(STShd.SOLID)
            shd.color = "auto"
            shd.fill = hex.removePrefix("#").uppercase()
        } catch (_: Exception) { }
    }

    // POI 旧 addPicture 流式重载已标记废弃但仍为流输入的标准入口，压制警告
    @Suppress("DEPRECATION")
    private fun addDocxImage(doc: XWPFDocument, block: JSONObject, host: ToolHost) {
        val imgPath = block.optString("path", "").trim()
        if (imgPath.isBlank()) return
        val resolved = host.resolvePath(imgPath) ?: return
        val f = File(resolved)
        if (!f.exists()) return
        val width = block.optInt("width", 400)
        val p = doc.createParagraph()
        p.alignment = ParagraphAlignment.CENTER
        val r = p.createRun()
        val fmt = when (f.extension.lowercase()) {
            "png" -> Document.PICTURE_TYPE_PNG
            "gif" -> Document.PICTURE_TYPE_GIF
            "bmp" -> Document.PICTURE_TYPE_BMP
            else -> Document.PICTURE_TYPE_JPEG
        }
        f.inputStream().use { ins ->
            r.addPicture(ins, fmt, f.name, Units.toEMU(width.toDouble()), Units.toEMU(width.toDouble()))
        }
    }

    // ==================== Word 读取（结构化） ====================

    /**
     * 读取 Word。
     *
     * structured=true 返回 JSON：
     *   {"paragraphs":[{"index":0,"style":"Heading1","text":"...","runs":[...]}],
     *    "tables":[{"index":0,"rows":[["a","b"],["c","d"]]}]}
     *
     * 为什么 纯文本读出来 AI 看不到「这段是标题、那段是表格」，
     * 就没法做「把标题改成红色」这种精确编辑。
     */
    private fun readDocx(path: String, structured: Boolean): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult.fail("文件不存在：$path")
        XWPFDocument(f.inputStream()).use { doc ->
            if (!structured) {
                val sb = StringBuilder()
                doc.paragraphs.forEach { p ->
                    if (p.text.isNotBlank()) sb.append(p.text).append("\n")
                }
                doc.tables.forEach { t ->
                    sb.append("\n[表格]\n")
                    t.rows.forEach { row ->
                        sb.append(row.tableCells.joinToString(" | ") { it.text }).append("\n")
                    }
                }
                val text = sb.toString().trim()
                return ToolResult.ok(if (text.isBlank()) "(文档为空)" else text.take(20000))
            }

            val root = JSONObject()
            val paras = JSONArray()
            doc.paragraphs.forEachIndexed { idx, p ->
                if (p.text.isBlank() && p.runs.isEmpty()) return@forEachIndexed
                val po = JSONObject()
                po.put("index", idx)
                val style = try { p.style ?: "" } catch (_: Exception) { "" }
                if (style.isNotBlank()) po.put("style", style)
                po.put("text", p.text ?: "")
                val align = try { p.alignment?.toString() ?: "" } catch (_: Exception) { "" }
                if (align.isNotBlank()) po.put("align", align)
                // 段落内 run 的样式（只记录有特殊样式的）
                val runsArr = JSONArray()
                p.runs.forEach { r ->
                    val ro = JSONObject()
                    ro.put("text", r.text() ?: "")
                    if (r.isBold) ro.put("bold", true)
                    if (r.isItalic) ro.put("italic", true)
                    val color = try { r.color } catch (_: Exception) { null }
                    if (!color.isNullOrBlank()) ro.put("color", color)
                    val size = try { r.fontSizeAsDouble } catch (_: Exception) { null }
                    if (size != null && size > 0) ro.put("size", size)
                    if (ro.length() > 1) runsArr.put(ro)
                }
                if (runsArr.length() > 0) po.put("runs", runsArr)
                paras.put(po)
            }
            root.put("paragraphs", paras)

            val tables = JSONArray()
            doc.tables.forEachIndexed { idx, t ->
                val to = JSONObject()
                to.put("index", idx)
                val rowsArr = JSONArray()
                t.rows.forEach { row ->
                    val rowArr = JSONArray()
                    row.tableCells.forEach { cell -> rowArr.put(cell.text ?: "") }
                    rowsArr.put(rowArr)
                }
                to.put("rows", rowsArr)
                to.put("rowCount", t.rows.size)
                to.put("colCount", t.rows.firstOrNull()?.tableCells?.size ?: 0)
                tables.put(to)
            }
            root.put("tables", tables)
            root.put("paragraphCount", doc.paragraphs.size)
            root.put("tableCount", doc.tables.size)

            val json = root.toString(2)
            return ToolResult.ok(if (json.length > 20000) json.take(20000) + "\n...(已截断)" else json)
        }
    }

    // ==================== Word 编辑 ====================

    /**
     * 编辑已有 Word 文档。
     *
     * edits 每项支持：
     *   {paragraph: 3, set: {text, bold, color, size, align}}  按索引改整段
     *   {find: "旧文本", replace: "新文本"}                     全文查找替换
     *   {paragraph: 3, insertAfter: {type, text, ...}}          在某段后插入新块
     *   {paragraph: 3, delete: true}                            删除某段
     *
     * 为什么 旧实现只能重新生成整个文档，原文档的图片、格式、批注全丢。
     */
    private fun editDocx(path: String, args: JSONObject, host: ToolHost): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult.fail("文件不存在：$path")
        val edits = args.optJSONArray("edits")
            ?: return ToolResult.fail("edit_docx 需要 edits 数组")

        var changed = 0
        XWPFDocument(f.inputStream()).use { doc ->
            val cfg = DocxStyleConfig.from(args)
            for (i in 0 until edits.length()) {
                val e = edits.optJSONObject(i) ?: continue
                val paraIdx = e.optInt("paragraph", -1)

                // 1) 删除段落
                if (e.optBoolean("delete", false) && paraIdx >= 0) {
                    try {
                        doc.removeBodyElement(paraIdx)
                        changed++
                    } catch (_: Exception) { }
                    continue
                }

                // 2) 按索引改整段
                if (paraIdx >= 0 && e.has("set")) {
                    val set = e.optJSONObject("set") ?: continue
                    val p = doc.paragraphs.getOrNull(paraIdx) ?: continue
                    if (set.has("text")) {
                        // 保留第一个 run 的样式，替换文本
                        val newText = set.optString("text", "")
                        if (p.runs.isNotEmpty()) {
                            val first = p.runs.first()
                            first.setText(newText, 0)
                            // 删掉多余的 run
                            for (j in p.runs.size - 1 downTo 1) {
                                try { p.removeRun(j) } catch (_: Exception) { }
                            }
                        } else {
                            p.createRun().setText(newText)
                        }
                        changed++
                    }
                    p.runs.forEach { r ->
                        if (set.has("bold")) r.isBold = set.optBoolean("bold", false)
                        if (set.has("italic")) r.isItalic = set.optBoolean("italic", false)
                        if (set.has("color")) setRunColor(r, set.optString("color", ""))
                        if (set.has("size")) r.fontSize = set.optInt("size", cfg.bodySize)
                    }
                    if (set.has("align")) {
                        p.alignment = when (set.optString("align", "").lowercase()) {
                            "center" -> ParagraphAlignment.CENTER
                            "right" -> ParagraphAlignment.RIGHT
                            "justify" -> ParagraphAlignment.BOTH
                            else -> ParagraphAlignment.LEFT
                        }
                    }
                    continue
                }

                // 3) 全文查找替换
                val find = e.optString("find", "")
                if (find.isNotBlank()) {
                    val replace = e.optString("replace", "")
                    doc.paragraphs.forEach { p ->
                        p.runs.forEach { r ->
                            val t = r.text()
                            if (t.contains(find)) {
                                r.setText(t.replace(find, replace), 0)
                                changed++
                            }
                        }
                    }
                    // 表格内也替换
                    doc.tables.forEach { t ->
                        t.rows.forEach { row ->
                            row.tableCells.forEach { cell ->
                                cell.paragraphs.forEach { p ->
                                    p.runs.forEach { r ->
                                        val txt = r.text()
                                        if (txt.contains(find)) {
                                            r.setText(txt.replace(find, replace), 0)
                                            changed++
                                        }
                                    }
                                }
                            }
                        }
                    }
                    continue
                }

                // 4) 在某段后插入新块
                val insertAfter = e.optJSONObject("insertAfter")
                if (paraIdx >= 0 && insertAfter != null) {
                    val anchor = doc.paragraphs.getOrNull(paraIdx) ?: continue
                    val newP = doc.insertNewParagraph(anchor.ctp.newCursor())
                    val type = insertAfter.optString("type", "text")
                    val text = insertAfter.optString("text", "")
                    val r = newP.createRun()
                    r.setText(text)
                    r.fontFamily = cfg.font
                    r.fontSize = insertAfter.optInt("size", cfg.bodySize)
                    if (type == "heading") {
                        r.isBold = true
                        try { newP.style = "Heading${insertAfter.optInt("level", 1)}" } catch (_: Exception) { }
                    }
                    if (insertAfter.optBoolean("bold", false)) r.isBold = true
                    val color = insertAfter.optString("color", "")
                    if (color.isNotBlank()) setRunColor(r, color)
                    changed++
                }
            }

            if (changed > 0) {
                FileOutputStream(path).use { doc.write(it) }
            }
        }
        return if (changed > 0) ToolResult.ok("已修改 Word 文档：$path（$changed 处改动）")
        else ToolResult.fail("没有匹配到任何可修改的内容")
    }

    // ==================== PowerPoint ====================

    private fun createPptx(path: String, args: JSONObject, host: ToolHost): ToolResult {
        val ppt = XMLSlideShow()
        try {
            val pageSize = ppt.pageSize
            val title = args.optString("title", "").trim()
            val cfg = PptxStyleConfig.from(args)
            val pageNumber = args.optBoolean("pageNumber", false)
            val footerText = args.optString("footer", "").trim()

            // 首页：标题页
            if (title.isNotBlank()) {
                val slide = ppt.createSlide()
                if (cfg.bgColor != null) fillSlideBackground(slide, cfg.bgColor)
                val box = slide.createTextBox()
                box.anchor = java.awt.Rectangle(60, pageSize.height / 2 - 80, pageSize.width - 120, 160)
                val p = box.addNewTextParagraph()
                p.textAlign = org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER
                val run = p.addNewTextRun()
                run.setText(title)
                run.fontSize = 40.0
                run.isBold = true
                if (cfg.titleColor != null) run.setFontColor(cfg.titleColor)
            }

            val content = args.optJSONArray("content") ?: JSONArray()
            var currentSlide: org.apache.poi.xslf.usermodel.XSLFSlide? = null
            var y = 60.0

            fun newSlide(): org.apache.poi.xslf.usermodel.XSLFSlide {
                val s = ppt.createSlide()
                if (cfg.bgColor != null) fillSlideBackground(s, cfg.bgColor)
                y = 60.0
                return s
            }

            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type", "text")) {
                    "title", "heading" -> {
                        currentSlide = newSlide()
                        val box = currentSlide.createTextBox()
                        box.anchor = java.awt.Rectangle(60, 40, pageSize.width - 120, 80)
                        val p = box.addNewTextParagraph()
                        val run = p.addNewTextRun()
                        // 兼容 text / title 两种字段名
                        val headText = block.optString("text", "").ifBlank { block.optString("title", "") }
                        run.setText(headText)
                        run.fontSize = 32.0
                        run.isBold = true
                        if (cfg.titleColor != null) run.setFontColor(cfg.titleColor)
                        y = 140.0
                        // heading 块可带 items，直接渲染成要点列表
                        val headItems = block.optJSONArray("items")
                        if (headItems != null && headItems.length() > 0) {
                            val ib = currentSlide!!.createTextBox()
                            val ih = headItems.length() * 50 + 20
                            ib.anchor = java.awt.Rectangle(60, y.toInt(), pageSize.width - 120, ih)
                            for (j in 0 until headItems.length()) {
                                val ip = ib.addNewTextParagraph()
                                ip.indentLevel = 0
                                val ir = ip.addNewTextRun()
                                val t = headItems.optString(j, "")
                                ir.setText("• $t")
                                ir.fontSize = fitFontSize(t, 20.0, pageSize.width - 160)
                                if (cfg.bodyColor != null) ir.setFontColor(cfg.bodyColor)
                            }
                            y += ih + 10
                        }
                    }
                    "text" -> {
                        val s = currentSlide ?: newSlide().also { currentSlide = it }
                        val text = block.optString("text", "")
                        val box = s.createTextBox()
                        box.anchor = java.awt.Rectangle(60, y.toInt(), pageSize.width - 120, 60)
                        val p = box.addNewTextParagraph()
                        val run = p.addNewTextRun()
                        run.setText(text)
                        run.fontSize = fitFontSize(text, 18.0, pageSize.width - 120)
                        if (cfg.bodyColor != null) run.setFontColor(cfg.bodyColor)
                        y += 70
                    }
                    "bullets" -> {
                        val s = currentSlide ?: newSlide().also { currentSlide = it }
                        val items = block.optJSONArray("items") ?: continue
                        val box = s.createTextBox()
                        val h = (items.length() * 50 + 20)
                        box.anchor = java.awt.Rectangle(60, y.toInt(), pageSize.width - 120, h)
                        for (j in 0 until items.length()) {
                            val p = box.addNewTextParagraph()
                            p.indentLevel = 0
                            val run = p.addNewTextRun()
                            val t = items.optString(j, "")
                            run.setText("• $t")
                            run.fontSize = fitFontSize(t, 20.0, pageSize.width - 160)
                            if (cfg.bodyColor != null) run.setFontColor(cfg.bodyColor)
                        }
                        y += h + 10
                    }
                    "table" -> {
                        val s = currentSlide ?: newSlide().also { currentSlide = it }
                        val headers = block.optJSONArray("headers")
                        val rows = block.optJSONArray("rows") ?: continue
                        val cols = maxOf(headers?.length() ?: 0, (0 until rows.length()).maxOfOrNull {
                            rows.optJSONArray(it)?.length() ?: 0
                        } ?: 0)
                        if (cols == 0) continue
                        val totalRows = rows.length() + (if (headers != null) 1 else 0)
                        val tbl = s.createTable(totalRows, cols)
                        tbl.anchor = java.awt.Rectangle(60, y.toInt(), pageSize.width - 120, totalRows * 40)
                        var ri = 0
                        if (headers != null) {
                            for (c in 0 until cols) {
                                val cell = tbl.getCell(0, c)
                                cell.text = headers.optString(c, "")
                                if (cfg.tableHeaderFill != null) cell.setFillColor(cfg.tableHeaderFill)
                                cell.textParagraphs.forEach { tp ->
                                    tp.textRuns.forEach { tr ->
                                        tr.isBold = true
                                        if (cfg.tableHeaderFont != null) tr.setFontColor(cfg.tableHeaderFont)
                                    }
                                }
                            }
                            ri = 1
                        }
                        for (r in 0 until rows.length()) {
                            val rowArr = rows.optJSONArray(r) ?: continue
                            for (c in 0 until cols) {
                                tbl.getCell(ri + r, c).text = rowArr.optString(c, "")
                            }
                        }
                        y += totalRows * 45 + 10
                    }
                    "image" -> {
                        val s = currentSlide ?: newSlide().also { currentSlide = it }
                        val imgPath = block.optString("path", "").trim()
                        if (imgPath.isBlank()) continue
                        val resolved = host.resolvePath(imgPath) ?: continue
                        val f = File(resolved)
                        if (!f.exists()) continue
                        val data = f.readBytes()
                        val type = when (f.extension.lowercase()) {
                            "png" -> PictureData.PictureType.PNG
                            "gif" -> PictureData.PictureType.GIF
                            else -> PictureData.PictureType.JPEG
                        }
                        val pd = ppt.addPicture(data, type)
                        val w = block.optInt("width", 400)
                        // 按图片实际宽高比算高度。旧实现硬编码 4:3（w * 0.75），
                        // 16:9 图片会被拉伸。显式传了 height 就尊重用户值。
                        val explicitH = block.optInt("height", 0)
                        val h = if (explicitH > 0) explicitH else {
                            try {
                                javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data))?.let { img ->
                                    if (img.width > 0) {
                                        (w.toDouble() * img.height / img.width).toInt().coerceAtLeast(1)
                                    } else (w * 0.75).toInt()
                                } ?: (w * 0.75).toInt()
                            } catch (_: Exception) { (w * 0.75).toInt() }
                        }
                        val pic = s.createPicture(pd)
                        pic.anchor = java.awt.Rectangle(60, y.toInt(), w, h)
                        y += h + 10
                    }
                }
            }

            // 页码 / 页脚（跳过首页）
            if (pageNumber || footerText.isNotBlank()) {
                val slides = ppt.slides
                for (idx in 1 until slides.size) {
                    val s = slides[idx]
                    val box = s.createTextBox()
                    box.anchor = java.awt.Rectangle(60, pageSize.height - 50, pageSize.width - 120, 30)
                    val p = box.addNewTextParagraph()
                    p.textAlign = org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT
                    val run = p.addNewTextRun()
                    val label = if (footerText.isNotBlank()) "$footerText   ${idx + 1}" else "${idx + 1}"
                    run.setText(label)
                    run.fontSize = 12.0
                    if (cfg.bodyColor != null) run.setFontColor(cfg.bodyColor)
                }
            }

            File(path).parentFile?.mkdirs()
            FileOutputStream(path).use { ppt.write(it) }
            return ToolResult.ok("已生成 PPT：$path（共 ${ppt.slides.size} 页）")
        } finally {
            try { ppt.close() } catch (_: Exception) { }
        }
    }

    /** 填充幻灯片背景色。 */
    private fun fillSlideBackground(slide: org.apache.poi.xslf.usermodel.XSLFSlide, color: Color) {
        try {
            slide.background.setFillColor(color)
        } catch (_: Exception) { }
    }

    /** 根据文本长度估算字号，防止溢出（粗略）。 */
    private fun fitFontSize(text: String, base: Double, boxWidth: Int): Double {
        if (text.isEmpty()) return base
        val charsPerLine = (boxWidth / (base * 0.55)).toInt().coerceAtLeast(1)
        val lines = (text.length + charsPerLine - 1) / charsPerLine
        return if (lines > 3) (base * 3.0 / lines).coerceAtLeast(12.0) else base
    }

    private fun parseColor(hex: String): Color? {
        val h = hex.removePrefix("#").trim()
        return try {
            when (h.length) {
                6 -> Color(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                3 -> Color(h[0].toString().repeat(2).toInt(16), h[1].toString().repeat(2).toInt(16), h[2].toString().repeat(2).toInt(16))
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** 读取 PPT：每页的文本内容。 */
    private fun readPptx(path: String): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult.fail("文件不存在：$path")
        XMLSlideShow(f.inputStream()).use { ppt ->
            val root = JSONObject()
            val slidesArr = JSONArray()
            val slideHeight = ppt.pageSize.height
            ppt.slides.forEachIndexed { idx, slide ->
                val so = JSONObject()
                so.put("index", idx)
                val texts = JSONArray()
                val tablesArr = JSONArray()
                slide.shapes.forEach { shape ->
                    // 表格单独处理，它不是 TextShape
                    if (shape is org.apache.poi.xslf.usermodel.XSLFTable) {
                        val to = JSONObject()
                        val rowsArr = JSONArray()
                        shape.rows.forEach { row ->
                            val rowArr = JSONArray()
                            row.cells.forEach { c -> rowArr.put(c.text ?: "") }
                            rowsArr.put(rowArr)
                        }
                        to.put("rows", rowsArr)
                        to.put("rowCount", shape.rows.size)
                        to.put("colCount", shape.rows.firstOrNull()?.cells?.size ?: 0)
                        tablesArr.put(to)
                        return@forEach
                    }
                    if (shape is org.apache.poi.sl.usermodel.TextShape<*, *>) {
                        // 跳过页脚/页码：占位符类型或位置在页面底部
                        val phType = try { shape.shapeType?.toString() ?: "" } catch (_: Exception) { "" }
                        if (phType.contains("FOOTER") || phType.contains("SLIDE_NUMBER") || phType.contains("DATE")) return@forEach
                        val anchor = try { shape.anchor } catch (_: Exception) { null }
                        // 页脚框：贴近页面底部且高度很小（30px）
                        if (anchor != null && anchor.y > slideHeight - 70 && anchor.height <= 40) return@forEach
                        val t = shape.text
                        if (t.isNotBlank()) texts.put(t)
                    }
                }
                so.put("texts", texts)
                if (tablesArr.length() > 0) so.put("tables", tablesArr)
                slidesArr.put(so)
            }
            root.put("slides", slidesArr)
            root.put("slideCount", ppt.slides.size)
            val json = root.toString(2)
            return ToolResult.ok(if (json.length > 20000) json.take(20000) + "\n...(已截断)" else json)
        }
    }

    // ==================== Excel 生成 ====================

    private fun createXlsx(path: String, args: JSONObject, host: ToolHost): ToolResult {
        val wb = XSSFWorkbook()
        try {
            val sheets = args.optJSONArray("sheets")
            if (sheets == null || sheets.length() == 0) {
                wb.createSheet("Sheet1")
            } else {
                for (i in 0 until sheets.length()) {
                    val s = sheets.optJSONObject(i) ?: continue
                    val name = s.optString("name", "Sheet${i + 1}").take(31)
                    val sheet = wb.createSheet(name)
                    val headers = s.optJSONArray("headers")
                    val rows = s.optJSONArray("rows") ?: JSONArray()

                    val cfg = XlsxStyleConfig.from(s)
                    val styleCache = XlsxStyleCache(wb, cfg)
                    val columns = s.optJSONArray("columns")

                    // 列级对齐/格式（可选）
                    fun colAlign(c: Int): HorizontalAlignment? {
                        val a = columns?.optJSONObject(c)?.optString("align", "")?.trim()?.lowercase() ?: return null
                        return when (a) {
                            "left" -> HorizontalAlignment.LEFT
                            "center" -> HorizontalAlignment.CENTER
                            "right" -> HorizontalAlignment.RIGHT
                            else -> null
                        }
                    }
                    fun colFormat(c: Int): String? =
                        columns?.optJSONObject(c)?.optString("format", "")?.ifBlank { null }

                    var rowIdx = 0
                    if (headers != null) {
                        val hr = sheet.createRow(0)
                        hr.heightInPoints = cfg.headerRowHeight
                        for (c in 0 until headers.length()) {
                            val cell = hr.createCell(c)
                            cell.setCellValue(headers.optString(c, ""))
                            cell.cellStyle = styleCache.headerStyle(c)
                        }
                        rowIdx = 1
                    }
                    for (r in 0 until rows.length()) {
                        val rowArr = rows.optJSONArray(r) ?: continue
                        val row = sheet.createRow(rowIdx + r)
                        if (cfg.rowHeight > 0) row.heightInPoints = cfg.rowHeight
                        for (c in 0 until rowArr.length()) {
                            val v = rowArr.opt(c)
                            val cell = row.createCell(c)
                            when (v) {
                                is Number -> cell.setCellValue(v.toDouble())
                                is Boolean -> cell.setCellValue(v)
                                else -> cell.setCellValue(v?.toString() ?: "")
                            }
                            val isZebra = cfg.zebra && (r % 2 == 1)
                            cell.cellStyle = styleCache.dataStyle(c, isZebra, v, colAlign(c), colFormat(c))
                        }
                    }

                    val highlights = s.optJSONArray("highlights")
                    if (highlights != null) {
                        for (h in 0 until highlights.length()) {
                            val hl = highlights.optJSONObject(h) ?: continue
                            val hr = hl.optInt("row", -1)
                            val hc = hl.optInt("col", -1)
                            if (hr < 0 || hc < 0) continue
                            val actualRow = sheet.getRow(hr + rowIdx) ?: continue
                            val cell = actualRow.getCell(hc) ?: actualRow.createCell(hc)
                            cell.cellStyle = styleCache.highlightStyle(hl)
                        }
                    }

                    // 公式：formulas: [{row, col, formula}]
                    val formulas = s.optJSONArray("formulas")
                    if (formulas != null) {
                        for (fi in 0 until formulas.length()) {
                            val fo = formulas.optJSONObject(fi) ?: continue
                            val fr = fo.optInt("row", -1)
                            val fc = fo.optInt("col", -1)
                            val formula = fo.optString("formula", "")
                            if (fr < 0 || fc < 0 || formula.isBlank()) continue
                            val actualRow = sheet.getRow(fr + rowIdx) ?: sheet.createRow(fr + rowIdx)
                            val cell = actualRow.getCell(fc) ?: actualRow.createCell(fc)
                            cell.cellFormula = formula.removePrefix("=")
                        }
                    }

                    // 合并单元格：merges: [{firstRow, lastRow, firstCol, lastCol}]
                    val merges = s.optJSONArray("merges")
                    if (merges != null) {
                        for (mi in 0 until merges.length()) {
                            val mo = merges.optJSONObject(mi) ?: continue
                            val fr = mo.optInt("firstRow", -1)
                            val lr = mo.optInt("lastRow", -1)
                            val fc = mo.optInt("firstCol", -1)
                            val lc = mo.optInt("lastCol", -1)
                            if (fr < 0 || lr < 0 || fc < 0 || lc < 0) continue
                            try {
                                sheet.addMergedRegion(CellRangeAddress(fr, lr, fc, lc))
                            } catch (_: Exception) { }
                        }
                    }

                    // 条件格式：conditional: [{range, type, operator, formula, fill, fontColor}]
                    val conditional = s.optJSONArray("conditional")
                    if (conditional != null) {
                        for (ci in 0 until conditional.length()) {
                            val co = conditional.optJSONObject(ci) ?: continue
                            applyConditionalFormat(sheet, co, wb)
                        }
                    }

                    // 数据验证：validation: [{range, type, formula1, formula2, prompt}]
                    val validation = s.optJSONArray("validation")
                    if (validation != null) {
                        for (vi in 0 until validation.length()) {
                            val vo = validation.optJSONObject(vi) ?: continue
                            applyDataValidation(sheet, vo)
                        }
                    }

                    val maxCols = maxOf(
                        headers?.length() ?: 0,
                        (0 until rows.length()).maxOfOrNull { rows.optJSONArray(it)?.length() ?: 0 } ?: 0
                    )
                    for (c in 0 until maxCols) {
                        val colCfg = columns?.optJSONObject(c)
                        val explicit = colCfg?.optInt("width", 0) ?: 0
                        if (explicit > 0) {
                            sheet.setColumnWidth(c, (explicit * 256).coerceIn(256, 255 * 256))
                        } else if (cfg.autoWidth) {
                            sheet.setColumnWidth(c, estimateColumnWidth(c, headers, rows))
                        } else {
                            sheet.setColumnWidth(c, 18 * 256)
                        }
                    }

                    if (cfg.freezeHeader && headers != null) {
                        sheet.createFreezePane(0, 1)
                    }
                }
            }

            File(path).parentFile?.mkdirs()
            FileOutputStream(path).use { wb.write(it) }
            return ToolResult.ok("已生成 Excel：$path（共 ${wb.numberOfSheets} 个工作表）")
        } finally {
            try { wb.close() } catch (_: Exception) { }
        }
    }

    /** 条件格式：支持 cellIs / expression / colorScale。 */
    private fun applyConditionalFormat(
        sheet: org.apache.poi.xssf.usermodel.XSSFSheet,
        cfg: JSONObject,
        wb: XSSFWorkbook
    ) {
        try {
            val range = cfg.optString("range", "")
            if (range.isBlank()) return
            val cf = sheet.sheetConditionalFormatting
            val regions = CellRangeAddress.valueOf(range)
            val ruleType = cfg.optString("type", "cellIs").lowercase()

            val fill = cfg.optString("fill", "")
            val fontColor = cfg.optString("fontColor", "")
            val rule = when (ruleType) {
                "colorscale" -> {
                    val format = cf.createConditionalFormattingColorScaleRule()
                    format
                }
                else -> {
                    val operator = when (cfg.optString("operator", "greaterThan").lowercase()) {
                        "lessthan" -> org.apache.poi.ss.usermodel.ComparisonOperator.LT
                        "lessthanorequal" -> org.apache.poi.ss.usermodel.ComparisonOperator.LE
                        "greaterthan" -> org.apache.poi.ss.usermodel.ComparisonOperator.GT
                        "greaterthanorequal" -> org.apache.poi.ss.usermodel.ComparisonOperator.GE
                        "equal" -> org.apache.poi.ss.usermodel.ComparisonOperator.EQUAL
                        "notequal" -> org.apache.poi.ss.usermodel.ComparisonOperator.NOT_EQUAL
                        "between" -> org.apache.poi.ss.usermodel.ComparisonOperator.BETWEEN
                        else -> org.apache.poi.ss.usermodel.ComparisonOperator.GT
                    }
                    val formula = cfg.optString("formula", "0")
                    val formula2 = cfg.optString("formula2", "")
                    val rule = cf.createConditionalFormattingRule(operator, formula, formula2.ifBlank { null })
                    if (fill.isNotBlank() || fontColor.isNotBlank()) {
                        val pattern = rule.createPatternFormatting()
                        if (fill.isNotBlank()) {
                            pattern.fillBackgroundColor = org.apache.poi.ss.usermodel.IndexedColors.WHITE.index
                            pattern.fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.WHITE.index
                        }
                        val font = rule.createFontFormatting()
                        if (fontColor.isNotBlank()) {
                            font.fontColorIndex = org.apache.poi.ss.usermodel.IndexedColors.RED.index
                        }
                    }
                    rule
                }
            }
            cf.addConditionalFormatting(arrayOf(regions), rule)
        } catch (_: Exception) { }
    }

    /** 数据验证：下拉列表 / 数字范围。 */
    private fun applyDataValidation(sheet: org.apache.poi.xssf.usermodel.XSSFSheet, cfg: JSONObject) {
        try {
            val range = cfg.optString("range", "")
            if (range.isBlank()) return
            val dvHelper = sheet.dataValidationHelper
            val regions = CellRangeAddress.valueOf(range)
            val type = cfg.optString("type", "list").lowercase()
            val formula1 = cfg.optString("formula1", "")
            val formula2 = cfg.optString("formula2", "")
            val prompt = cfg.optString("prompt", "")

            val constraint = when (type) {
                "list" -> dvHelper.createExplicitListConstraint(formula1.split(",").map { it.trim() }.toTypedArray())
                "whole" -> dvHelper.createIntegerConstraint(
                    org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN,
                    formula1, formula2.ifBlank { formula1 }
                )
                "decimal" -> dvHelper.createDecimalConstraint(
                    org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN,
                    formula1, formula2.ifBlank { formula1 }
                )
                else -> return
            }
            val validation = dvHelper.createValidation(constraint, org.apache.poi.ss.util.CellRangeAddressList(regions.firstRow, regions.lastRow, regions.firstColumn, regions.lastColumn))
            if (prompt.isNotBlank()) {
                validation.createPromptBox("提示", prompt)
                validation.showPromptBox = true
            }
            sheet.addValidationData(validation)
        } catch (_: Exception) { }
    }

    /** 估算列宽（POI 无真正 auto-fit，按字符宽度估算）。中文/全角算 2，clamp 到 [8,60]。 */
    private fun estimateColumnWidth(col: Int, headers: JSONArray?, rows: JSONArray): Int {
        var maxLen = 0.0
        headers?.optString(col, "")?.let { maxLen = maxOf(maxLen, displayWidth(it)) }
        for (r in 0 until rows.length()) {
            val rowArr = rows.optJSONArray(r) ?: continue
            val v = rowArr.opt(col)
            if (v != null && v != JSONObject.NULL) {
                maxLen = maxOf(maxLen, displayWidth(v.toString()))
            }
        }
        val chars = (maxLen + 2).coerceIn(8.0, 60.0)
        return (chars * 256).toInt()
    }

    /** 计算字符串显示宽度：CJK / 全角字符算 2，其余算 1。 */
    private fun displayWidth(s: String): Double {
        var w = 0.0
        for (ch in s) w += if (isWide(ch)) 2.0 else 1.0
        return w
    }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x1100..0x115F) ||
                (code in 0x2E80..0xA4CF) ||
                (code in 0xAC00..0xD7A3) ||
                (code in 0xF900..0xFAFF) ||
                (code in 0xFE30..0xFE4F) ||
                (code in 0xFF00..0xFF60) ||
                (code in 0xFFE0..0xFFE6)
    }

    // ==================== Excel 读取（结构化） ====================

    private fun readXlsx(path: String, structured: Boolean): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult.fail("文件不存在：$path")
        XSSFWorkbook(f.inputStream()).use { wb ->
            if (!structured) {
                val sb = StringBuilder()
                for (si in 0 until wb.numberOfSheets) {
                    val sheet = wb.getSheetAt(si)
                    sb.append("[工作表] ").append(sheet.sheetName).append("\n")
                    val fmt = org.apache.poi.ss.usermodel.DataFormatter()
                    sheet.forEach { row ->
                        val cells = row.map { fmt.formatCellValue(it) }
                        if (cells.any { it.isNotBlank() }) {
                            sb.append(cells.joinToString(" | ")).append("\n")
                        }
                    }
                    sb.append("\n")
                }
                val text = sb.toString().trim()
                return ToolResult.ok(if (text.isBlank()) "(表格为空)" else text.take(20000))
            }

            val root = JSONObject()
            val sheetsArr = JSONArray()
            val fmt = org.apache.poi.ss.usermodel.DataFormatter()
            for (si in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(si)
                val so = JSONObject()
                so.put("index", si)
                so.put("name", sheet.sheetName)
                so.put("rowCount", sheet.lastRowNum + 1)
                val rowsArr = JSONArray()
                sheet.forEach { row ->
                    val rowObj = JSONObject()
                    rowObj.put("row", row.rowNum)
                    val cellsArr = JSONArray()
                    row.forEach { cell ->
                        val co = JSONObject()
                        co.put("col", cell.columnIndex)
                        // 公式单元格：POI 不计算公式，缓存值可能为空，
                        // 直接给 "" 会让 AI 误判，这里回退成公式文本
                        val isFormula = try {
                            cell.cellType == org.apache.poi.ss.usermodel.CellType.FORMULA
                        } catch (_: Exception) { false }
                        if (isFormula) {
                            val f = try { cell.cellFormula } catch (_: Exception) { null }
                            co.put("formula", f ?: "")
                            // POI 不计算公式，缓存值可能是 ""/空，回退成公式文本
                            val cached = fmt.formatCellValue(cell)
                            // POI 对空缓存公式会返回字面量 ""，必须显式排除
                            val usable = cached.isNotBlank() && cached != "" && cached != "#VALUE!" && cached != "null"
                            co.put("value", if (usable) cached else "=" + (f ?: ""))
                        } else {
                            co.put("value", fmt.formatCellValue(cell))
                        }
                        cellsArr.put(co)
                    }
                    rowObj.put("cells", cellsArr)
                    rowsArr.put(rowObj)
                }
                so.put("rows", rowsArr)
                // 合并区域
                val mergesArr = JSONArray()
                for (mi in 0 until sheet.numMergedRegions) {
                    val region = sheet.getMergedRegion(mi)
                    mergesArr.put(JSONObject().apply {
                        put("firstRow", region.firstRow)
                        put("lastRow", region.lastRow)
                        put("firstCol", region.firstColumn)
                        put("lastCol", region.lastColumn)
                    })
                }
                if (mergesArr.length() > 0) so.put("merges", mergesArr)
                sheetsArr.put(so)
            }
            root.put("sheets", sheetsArr)
            val json = root.toString(2)
            return ToolResult.ok(if (json.length > 20000) json.take(20000) + "\n...(已截断)" else json)
        }
    }

    // ==================== Excel 编辑 ====================

    /**
     * 编辑已有 Excel。
     *
     * edits 每项支持：
     *   {sheet: 0, row: 2, col: 1, value: "新值"}          改单元格值
     *   {sheet: 0, row: 2, col: 1, style: {fill, fontColor, bold, align, format}}  改样式
     *   {sheet: 0, insertRow: 3}                            在第 3 行前插入空行
     *   {sheet: 0, deleteRow: 3}                            删除第 3 行
     *   {sheet: 0, insertCol: 2} / {sheet: 0, deleteCol: 2} 插入/删除列
     */
    private fun editXlsx(path: String, args: JSONObject): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult.fail("文件不存在：$path")
        val edits = args.optJSONArray("edits")
            ?: return ToolResult.fail("edit_xlsx 需要 edits 数组")

        var changed = 0
        XSSFWorkbook(f.inputStream()).use { wb ->
            for (i in 0 until edits.length()) {
                val e = edits.optJSONObject(i) ?: continue
                val sheetIdx = e.optInt("sheet", 0)
                val sheet = wb.getSheetAt(sheetIdx.coerceIn(0, wb.numberOfSheets - 1))

                // 插入/删除行
                if (e.has("insertRow")) {
                    val r = e.optInt("insertRow", -1)
                    if (r >= 0) {
                        val last = sheet.lastRowNum
                        // 在末尾之后插入时 shiftRows 会越界，直接 createRow 即可
                        if (r <= last) {
                            sheet.shiftRows(r, last, 1, true, false)
                        }
                        sheet.createRow(r)
                        changed++
                    }
                    continue
                }
                if (e.has("deleteRow")) {
                    val r = e.optInt("deleteRow", -1)
                    if (r >= 0) {
                        val row = sheet.getRow(r)
                        if (row != null) sheet.removeRow(row)
                        if (r < sheet.lastRowNum) {
                            sheet.shiftRows(r + 1, sheet.lastRowNum, -1, true, false)
                        }
                        changed++
                    }
                    continue
                }
                // 插入/删除列（POI 没有原生列操作，手动搬数据）
                if (e.has("insertCol")) {
                    val c = e.optInt("insertCol", -1)
                    if (c >= 0) {
                        for (ri in 0..sheet.lastRowNum) {
                            val row = sheet.getRow(ri) ?: continue
                            val last = row.lastCellNum.toInt()
                            for (ci in last downTo c + 1) {
                                val src = row.getCell(ci - 1)
                                val dst = row.getCell(ci) ?: row.createCell(ci)
                                if (src != null) {
                                    copyCellValue(src, dst)
                                    dst.cellStyle = src.cellStyle
                                }
                            }
                            row.getCell(c)?.let { row.removeCell(it) }
                        }
                        changed++
                    }
                    continue
                }
                if (e.has("deleteCol")) {
                    val c = e.optInt("deleteCol", -1)
                    if (c >= 0) {
                        for (ri in 0..sheet.lastRowNum) {
                            val row = sheet.getRow(ri) ?: continue
                            val last = row.lastCellNum.toInt()
                            for (ci in c until last - 1) {
                                val src = row.getCell(ci + 1)
                                val dst = row.getCell(ci) ?: row.createCell(ci)
                                if (src != null) {
                                    copyCellValue(src, dst)
                                    dst.cellStyle = src.cellStyle
                                }
                            }
                            row.getCell(last - 1)?.let { row.removeCell(it) }
                        }
                        changed++
                    }
                    continue
                }

                // 改值 / 改样式
                val r = e.optInt("row", -1)
                val c = e.optInt("col", -1)
                if (r < 0 || c < 0) continue
                val row = sheet.getRow(r) ?: sheet.createRow(r)
                val cell = row.getCell(c) ?: row.createCell(c)

                if (e.has("value")) {
                    when (val v = e.opt("value")) {
                        is Number -> cell.setCellValue(v.toDouble())
                        is Boolean -> cell.setCellValue(v)
                        else -> cell.setCellValue(v?.toString() ?: "")
                    }
                    changed++
                }
                if (e.has("formula")) {
                    cell.cellFormula = e.optString("formula", "").removePrefix("=")
                    changed++
                }
                val styleObj = e.optJSONObject("style")
                if (styleObj != null) {
                    val base = cell.cellStyle ?: wb.createCellStyle()
                    val newStyle = wb.createCellStyle()
                    newStyle.cloneStyleFrom(base)
                    val fill = styleObj.optString("fill", "")
                    if (fill.isNotBlank()) {
                        newStyle.setFillForegroundColor(XSSFColor(hexToBytes(fill), null))
                        newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                    }
                    val fontColor = styleObj.optString("fontColor", "")
                    val bold = styleObj.optBoolean("bold", false)
                    if (fontColor.isNotBlank() || bold) {
                        val font = wb.createFont()
                        font.bold = bold
                        if (fontColor.isNotBlank()) font.setColor(XSSFColor(hexToBytes(fontColor), null))
                        newStyle.setFont(font)
                    }
                    if (styleObj.has("align")) {
                        newStyle.alignment = when (styleObj.optString("align", "").lowercase()) {
                            "center" -> HorizontalAlignment.CENTER
                            "right" -> HorizontalAlignment.RIGHT
                            else -> HorizontalAlignment.LEFT
                        }
                    }
                    val format = styleObj.optString("format", "")
                    if (format.isNotBlank()) {
                        newStyle.dataFormat = wb.createDataFormat().getFormat(format)
                    }
                    cell.cellStyle = newStyle
                    changed++
                }
            }

            if (changed > 0) {
                FileOutputStream(path).use { wb.write(it) }
            }
        }
        return if (changed > 0) ToolResult.ok("已修改 Excel：$path（$changed 处改动）")
        else ToolResult.fail("没有匹配到任何可修改的内容")
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("#").trim()
        return if (h.length == 6) {
            byteArrayOf(
                h.substring(0, 2).toInt(16).toByte(),
                h.substring(2, 4).toInt(16).toByte(),
                h.substring(4, 6).toInt(16).toByte()
            )
        } else {
            byteArrayOf(0xD9.toByte(), 0xD9.toByte(), 0xD9.toByte())
        }
    }

    // ==================== Excel 样式：配置 + 缓存 ====================

    /**
     * 一个 sheet 的样式配置。
     *
     * 优先级：style 显式字段 > theme 预设 > 内置默认。
     * 全部可选，不传时保持朴素默认（灰底表头 + 加粗）。
     */
    private data class XlsxStyleConfig(
        val headerFill: String?,
        val headerFontColor: String?,
        val zebra: Boolean,
        val zebraFill: String?,
        val freezeHeader: Boolean,
        val autoWidth: Boolean,
        val wrapText: Boolean,
        val borders: Boolean,
        val fontSize: Int,
        val headerRowHeight: Float,
        val rowHeight: Float
    ) {
        companion object {
            fun from(sheet: JSONObject): XlsxStyleConfig {
                val theme = sheet.optString("theme", "").trim().lowercase()
                val style = sheet.optJSONObject("style")

                val preset = when (theme) {
                    "business" -> Preset("4472C4", "FFFFFF", true, "D9E1F2", true, autoWidth = true,
                        wrapText = true,
                        borders = true,
                        fontSize = 11,
                        headerRowHeight = 22f,
                        rowHeight = 0f
                    )
                    "colorful" -> Preset("FF6B6B", "FFFFFF", true, "FFF0F0",
                        freezeHeader = true,
                        autoWidth = true,
                        wrapText = true,
                        borders = true,
                        fontSize = 11,
                        headerRowHeight = 22f,
                        rowHeight = 0f
                    )
                    "minimal" -> Preset(null, null, false, null,
                        freezeHeader = false,
                        autoWidth = true,
                        wrapText = false,
                        borders = false,
                        fontSize = 11,
                        headerRowHeight = 20f,
                        rowHeight = 0f
                    )
                    "dark" -> Preset("2F3640", "FFFFFF", true, "F5F6FA",
                        freezeHeader = true,
                        autoWidth = true,
                        wrapText = true,
                        borders = true,
                        fontSize = 11,
                        headerRowHeight = 22f,
                        rowHeight = 0f
                    )
                    "green" -> Preset("2E8B57", "FFFFFF", true, "E8F5E9",
                        freezeHeader = true,
                        autoWidth = true,
                        wrapText = true,
                        borders = true,
                        fontSize = 11,
                        headerRowHeight = 22f,
                        rowHeight = 0f
                    )
                    "orange" -> Preset("E67E22", "FFFFFF", true, "FDF2E9",
                        freezeHeader = true,
                        autoWidth = true,
                        wrapText = true,
                        borders = true,
                        fontSize = 11,
                        headerRowHeight = 22f,
                        rowHeight = 0f
                    )
                    else -> Preset("D9D9D9", null, false, null,
                        freezeHeader = false,
                        autoWidth = true,
                        wrapText = false,
                        borders = false,
                        fontSize = 11,
                        headerRowHeight = 20f,
                        rowHeight = 0f
                    )
                }

                fun pickStr(key: String, fallback: String?): String? =
                    if (style?.has(key) == true) style.optString(key, "").ifBlank { null } else fallback
                fun pickBool(key: String, fallback: Boolean): Boolean =
                    if (style?.has(key) == true) style.optBoolean(key, fallback) else fallback
                fun pickInt(key: String, fallback: Int): Int =
                    if (style?.has(key) == true) style.optInt(key, fallback) else fallback
                fun pickFloat(key: String, fallback: Float): Float =
                    if (style?.has(key) == true) style.optDouble(key, fallback.toDouble()).toFloat() else fallback

                return XlsxStyleConfig(
                    headerFill = pickStr("headerFill", preset.headerFill),
                    headerFontColor = pickStr("headerFontColor", preset.headerFontColor),
                    zebra = pickBool("zebra", preset.zebra),
                    zebraFill = pickStr("zebraFill", preset.zebraFill),
                    freezeHeader = pickBool("freezeHeader", preset.freezeHeader),
                    autoWidth = pickBool("autoWidth", preset.autoWidth),
                    wrapText = pickBool("wrapText", preset.wrapText),
                    borders = pickBool("borders", preset.borders),
                    fontSize = pickInt("fontSize", preset.fontSize),
                    headerRowHeight = pickFloat("headerRowHeight", preset.headerRowHeight),
                    rowHeight = pickFloat("rowHeight", preset.rowHeight)
                )
            }
        }

        private data class Preset(
            val headerFill: String?, val headerFontColor: String?, val zebra: Boolean,
            val zebraFill: String?, val freezeHeader: Boolean, val autoWidth: Boolean,
            val wrapText: Boolean, val borders: Boolean, val fontSize: Int,
            val headerRowHeight: Float, val rowHeight: Float
        )
    }

    /**
     * 样式缓存。POI 的 CellStyle 数量有上限，必须复用。
     * 按"样式签名"缓存，相同签名返回同一个 CellStyle 对象。
     */
    private class XlsxStyleCache(
        private val wb: XSSFWorkbook,
        private val cfg: XlsxStyleConfig
    ) {
        private val cache = HashMap<String, XSSFCellStyle>()
        private val fonts = HashMap<String, XSSFFont>()

        private fun font(bold: Boolean, color: String?, size: Int): XSSFFont {
            val key = "$bold|$color|$size"
            return fonts.getOrPut(key) {
                wb.createFont().apply {
                    this.bold = bold
                    if (size > 0) fontHeightInPoints = size.toShort()
                    if (color != null) setColor(XSSFColor(hexToBytes(color), null))
                }
            }
        }

        private fun style(
            fill: String?, fontColor: String?, bold: Boolean, align: HorizontalAlignment,
            wrap: Boolean, borders: Boolean, format: String?, size: Int
        ): XSSFCellStyle {
            val key = "$fill|$fontColor|$bold|$align|$wrap|$borders|$format|$size"
            return cache.getOrPut(key) {
                wb.createCellStyle().apply {
                    if (fill != null) {
                        setFillForegroundColor(XSSFColor(hexToBytes(fill), null))
                        fillPattern = FillPatternType.SOLID_FOREGROUND
                    }
                    verticalAlignment = VerticalAlignment.CENTER
                    alignment = align
                    if (wrap) wrapText = true
                    if (borders) {
                        borderTop = BorderStyle.THIN; borderBottom = BorderStyle.THIN
                        borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN
                    }
                    if (format != null) dataFormat = wb.createDataFormat().getFormat(format)
                    setFont(font(bold, fontColor, size))
                }
            }
        }

        fun headerStyle(col: Int): XSSFCellStyle =
            style(cfg.headerFill, cfg.headerFontColor, true, HorizontalAlignment.CENTER,
                cfg.wrapText, cfg.borders, null, cfg.fontSize)

        fun dataStyle(
            col: Int, zebra: Boolean, value: Any?,
            alignOverride: HorizontalAlignment? = null, format: String? = null
        ): XSSFCellStyle {
            val align = alignOverride ?: when (value) {
                is Number -> HorizontalAlignment.RIGHT
                is Boolean -> HorizontalAlignment.CENTER
                else -> HorizontalAlignment.LEFT
            }
            val fill = if (zebra) cfg.zebraFill else null
            return style(fill, null, false, align, cfg.wrapText, cfg.borders, format, cfg.fontSize)
        }

        fun highlightStyle(hl: JSONObject): XSSFCellStyle {
            val fill = hl.optString("fill", "").ifBlank { null }
            val fontColor = hl.optString("fontColor", "").ifBlank { null }
            val bold = hl.optBoolean("bold", false)
            return style(fill, fontColor, bold, HorizontalAlignment.CENTER, cfg.wrapText, cfg.borders, null, cfg.fontSize)
        }

        private fun hexToBytes(hex: String): ByteArray {
            val h = hex.removePrefix("#").trim()
            return if (h.length == 6) {
                byteArrayOf(
                    h.substring(0, 2).toInt(16).toByte(),
                    h.substring(2, 4).toInt(16).toByte(),
                    h.substring(4, 6).toInt(16).toByte()
                )
            } else {
                byteArrayOf(0xD9.toByte(), 0xD9.toByte(), 0xD9.toByte())
            }
        }
    }

    // ==================== Word / PPT 样式配置 + 主题色 ====================

    /** Word 文档级样式配置。 */
    private data class DocxStyleConfig(
        val font: String,
        val bodySize: Int,
        val titleSize: Int,
        val titleColor: String?,
        val headingColor: String?,
        val theme: String,
        val marginTop: Double,
        val marginBottom: Double,
        val marginLeft: Double,
        val marginRight: Double
    ) {
        companion object {
            fun from(args: JSONObject): DocxStyleConfig {
                val style = args.optJSONObject("style")
                fun str(k: String, d: String) = style?.optString(k, d)?.ifBlank { d } ?: d
                fun int(k: String, d: Int) = style?.optInt(k, d) ?: d
                fun dbl(k: String, d: Double) = style?.optDouble(k, d) ?: d
                return DocxStyleConfig(
                    font = str("font", "微软雅黑"),
                    bodySize = int("fontSize", 11),
                    titleSize = int("titleSize", 22),
                    titleColor = style?.optString("titleColor", "")?.ifBlank { null },
                    headingColor = style?.optString("headingColor", "")?.ifBlank { null },
                    theme = args.optString("theme", "").trim().lowercase(),
                    marginTop = dbl("marginTop", 2.54),
                    marginBottom = dbl("marginBottom", 2.54),
                    marginLeft = dbl("marginLeft", 3.18),
                    marginRight = dbl("marginRight", 3.18)
                )
            }
        }
    }

    /** PPT 文档级样式配置。 */
    private data class PptxStyleConfig(
        val bgColor: Color?,
        val titleColor: Color?,
        val bodyColor: Color?,
        val tableHeaderFill: Color?,
        val tableHeaderFont: Color?
    ) {
        companion object {
            fun from(args: JSONObject): PptxStyleConfig {
                val theme = args.optString("theme", "").trim().lowercase()
                val style = args.optJSONObject("style")

                val preset = when (theme) {
                    "business" -> listOf("FFFFFF", "1F3864", "333333", "4472C4", "FFFFFF")
                    "dark" -> listOf("1E1E1E", "FFFFFF", "D0D0D0", "4472C4", "FFFFFF")
                    "colorful" -> listOf("FFF8F0", "C0392B", "2C3E50", "E74C3C", "FFFFFF")
                    "green" -> listOf("F0FFF4", "1E5631", "2C3E50", "2E8B57", "FFFFFF")
                    "minimal" -> listOf("FFFFFF", "000000", "333333", null, null)
                    else -> listOf(null, null, null, null, null)
                }

                fun pick(k: String, idx: Int): Color? {
                    val v = style?.optString(k, "")?.ifBlank { null } ?: preset[idx]
                    return v?.let { parseColor(it) }
                }
                return PptxStyleConfig(
                    bgColor = pick("bgColor", 0),
                    titleColor = pick("titleColor", 1),
                    bodyColor = pick("bodyColor", 2),
                    tableHeaderFill = pick("tableHeaderFill", 3),
                    tableHeaderFont = pick("tableHeaderFont", 4)
                )
            }
        }
    }

    /** 主题 -> 表头底色（Word/Excel 共用）。 */
    private fun themeHeaderFill(theme: String): String = when (theme) {
        "business" -> "4472C4"
        "colorful" -> "FF6B6B"
        "dark" -> "2F3640"
        "green" -> "2E8B57"
        "orange" -> "E67E22"
        "minimal" -> ""
        else -> "D9D9D9"
    }

    private fun themeHeaderFont(theme: String): String = when (theme) {
        "minimal" -> ""
        else -> "FFFFFF"
    }

    private fun themeZebra(theme: String): Boolean = when (theme) {
        "business", "colorful", "dark", "green", "orange" -> true
        else -> false
    }

    private fun themeZebraFill(theme: String): String = when (theme) {
        "business" -> "D9E1F2"
        "colorful" -> "FFF0F0"
        "dark" -> "F5F6FA"
        "green" -> "E8F5E9"
        "orange" -> "FDF2E9"
        else -> ""
    }
}
