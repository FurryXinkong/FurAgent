package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolHost
import net.xinkong.furagent.shared.tools.ToolResult
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.util.Units
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.Rectangle
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger

/**
 * 文档生成工具（仅桌面端）。
 *
 * 用 Apache POI 纯 Java 生成 Word / Excel / PPT，不依赖 Python、不依赖 Office。
 *
 * 设计原则：
 *   - 一个工具 `document`，用 op 分发（省 token，目录里只多一个名字）
 *   - content 用结构化 JSON 数组，模型生成 JSON 最稳，不用写 XML/HTML
 *   - docx / pptx 共用同一套 content 块结构，降低模型学习成本
 *
 * content 块类型（docx / pptx 通用）：
 *   {"type":"title",   "text":"标题"}
 *   {"type":"heading", "text":"小标题", "level":1}
 *   {"type":"text",    "text":"正文段落"}
 *   {"type":"bullets", "items":["要点1","要点2"]}
 *   {"type":"table",   "headers":["列1","列2"], "rows":[["a","b"],["c","d"]]}
 *   {"type":"image",   "path":"C:/x.png", "width":400}
 *   {"type":"pagebreak"}   // 仅 docx
 *
 * xlsx 用单独的 sheets 结构：
 *   {"sheets":[{"name":"Sheet1","rows":[["a","b"],["c","d"]],"headers":["列1","列2"]}]}
 */
object DocumentTools {

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
            description = "生成或读取 Word/Excel/PPT 文档（纯本地，无需 Office/Python）。" +
                    "op=create_docx 生成 Word；op=create_xlsx 生成 Excel；op=create_pptx 生成 PPT；" +
                    "op=read_docx / read_xlsx 读取内容。" +
                    "生成时用 content 数组描述内容，块类型：title/heading/text/bullets/table/image/pagebreak。" +
                    "【美化】三种文档都支持顶层 theme 一键套用风格（business/colorful/minimal/dark/green/orange）。" +
                    "Word：顶层 style 可设 font/fontSize/titleSize/titleColor/headingColor/marginTop 等；" +
                    "text 块支持 color/align/bold/size；table 块支持 theme/headerFill/headerFontColor/zebra/zebraFill。" +
                    "PPT：顶层 style 可设 bgColor/titleColor/bodyColor/tableHeaderFill/tableHeaderFont；" +
                    "支持 footer 与 pageNumber。" +
                    "Excel：sheets 每项支持 theme/style/columns/highlights（详见 sheets 描述）。" +
                    "不传 theme/style 时保持朴素默认样式。生成后返回文件路径。",
            paramsSchema = """{"type":"object","properties":{""" +
                    """"op":{"type":"string","enum":["create_docx","create_xlsx","create_pptx","read_docx","read_xlsx"]},""" +
                    """"path":{"type":"string","description":"输出/读取的文件路径，支持 ~ 和中文别名（桌面/文档/下载）"},""" +
                    """"title":{"type":"string","description":"文档标题（可选，会作为首行大标题）"},""" +
                    """"theme":{"type":"string","description":"全局风格预设：business/colorful/minimal/dark/green/orange"},""" +
                    """"style":{"type":"object","description":"精细样式（Word/PPT 用），字段见工具说明"},""" +
                    """"footer":{"type":"string","description":"页脚文本（PPT 用）"},""" +
                    """"pageNumber":{"type":"boolean","description":"是否显示页码（PPT 用）"},""" +
                    """"content":{"type":"array","description":"内容块数组（docx/pptx 用）","items":{"type":"object"}},""" +
                    """"sheets":{"type":"array","description":"工作表数组（xlsx 用）。每项：{name, headers, rows, theme?, style?, columns?, highlights?}","items":{"type":"object"}}""" +
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
                    "read_docx" -> readDocx(path)
                    "read_xlsx" -> readXlsx(path)
                    else -> ToolResult.fail("未知 op：$op")
                }
            } catch (e: Exception) {
                DesktopLogger.error(
                    "DocumentTools",
                    "生成失败：${e.javaClass.simpleName}: ${e.message}"
                )
                ToolResult.fail("生成失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ============================================================
    // Word (.docx)
    // ============================================================

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
                        org.apache.poi.xwpf.usermodel.BreakType.PAGE
                    )
                }
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

    private fun setRunColor(run: org.apache.poi.xwpf.usermodel.XWPFRun, hex: String) {
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
        val r = p.createRun()
        r.setText(block.optString("text", ""))
        r.isBold = true
        r.fontSize = block.optInt("size", when (level.coerceIn(1, 3)) { 1 -> 16; 2 -> 14; else -> 12 })
        r.fontFamily = cfg.font
        val color = block.optString("color", "").ifBlank { cfg.headingColor ?: "" }
        if (color.isNotBlank()) setRunColor(r, color)
    }

    private fun addDocxText(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val p = doc.createParagraph()
        p.alignment = when (block.optString("align", "").lowercase()) {
            "center" -> ParagraphAlignment.CENTER
            "right" -> ParagraphAlignment.RIGHT
            "justify" -> ParagraphAlignment.BOTH
            else -> ParagraphAlignment.LEFT
        }
        val r = p.createRun()
        r.setText(block.optString("text", ""))
        r.fontSize = block.optInt("size", cfg.bodySize)
        r.fontFamily = cfg.font
        if (block.optBoolean("bold", false)) r.isBold = true
        val color = block.optString("color", "")
        if (color.isNotBlank()) setRunColor(r, color)
    }

    private fun addDocxBullets(doc: XWPFDocument, block: JSONObject, cfg: DocxStyleConfig) {
        val items = block.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            val p = doc.createParagraph()
            p.style = "ListBullet"
            val r = p.createRun()
            r.setText(items.optString(i, ""))
            r.fontSize = cfg.bodySize
            r.fontFamily = cfg.font
        }
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
        // 列宽均分
        val colWidth = totalWidth / cols

        var rowIdx = 0
        if (headers != null) {
            for (c in 0 until cols) {
                val cell = table.getRow(0).getCell(c)
                setCellText(cell, headers.optString(c, ""), cfg.font, cfg.bodySize, true, headerFontColor)
                if (headerFill.isNotBlank()) setCellShading(cell, headerFill)
                cell.verticalAlignment = XWPFTableCell.XWPFVertAlign.CENTER
                try { cell.setWidth(colWidth.toString()) } catch (_: Exception) { }
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
                try { cell.setWidth(colWidth.toString()) } catch (_: Exception) { }
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
        if (color != null && color.isNotBlank()) setRunColor(run, color)
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
            "png" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG
            "gif" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF
            "bmp" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_BMP
            else -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG
        }
        f.inputStream().use { ins ->
            r.addPicture(ins, fmt, f.name, Units.toEMU(width.toDouble()), Units.toEMU(width.toDouble()))
        }
    }

    // ============================================================
    // PowerPoint (.pptx)
    // ============================================================

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
                box.anchor = Rectangle(60, (pageSize.height / 2 - 80).toInt(), pageSize.width - 120, 160)
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
                        val box = currentSlide!!.createTextBox()
                        box.anchor = Rectangle(60, 40, pageSize.width - 120, 80)
                        val p = box.addNewTextParagraph()
                        val run = p.addNewTextRun()
                        run.setText(block.optString("text", ""))
                        run.fontSize = 32.0
                        run.isBold = true
                        if (cfg.titleColor != null) run.setFontColor(cfg.titleColor)
                        y = 140.0
                    }
                    "text" -> {
                        val s = currentSlide ?: newSlide().also { currentSlide = it }
                        val text = block.optString("text", "")
                        val box = s.createTextBox()
                        box.anchor = Rectangle(60, y.toInt(), pageSize.width - 120, 60)
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
                        box.anchor = Rectangle(60, y.toInt(), pageSize.width - 120, h)
                        for (j in 0 until items.length()) {
                            val p = box.addNewTextParagraph()
                            p.indentLevel = 0
                            val run = p.addNewTextRun()
                            val t = items.optString(j, "")
                            run.setText("• " + t)
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
                        tbl.anchor = Rectangle(60, y.toInt(), pageSize.width - 120, totalRows * 40)
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
                        val h = block.optInt("height", (w * 0.75).toInt())
                        val pic = s.createPicture(pd)
                        pic.anchor = Rectangle(60, y.toInt(), w, h)
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
                    box.anchor = Rectangle(60, pageSize.height - 50, pageSize.width - 120, 30)
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

    // ============================================================
    // Excel (.xlsx)
    // ============================================================

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

    // ============================================================
    // 读取
    // ============================================================

    private fun readDocx(path: String): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult.fail("文件不存在：$path")
        XWPFDocument(f.inputStream()).use { doc ->
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
    }

    private fun readXlsx(path: String): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult.fail("文件不存在：$path")
        XSSFWorkbook(f.inputStream()).use { wb ->
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
    }

    // ============================================================
    // Excel 样式：配置 + 缓存
    // ============================================================

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
                    "business" -> Preset("4472C4", "FFFFFF", true, "D9E1F2", true, true, true, true, 11, 22f, 0f)
                    "colorful" -> Preset("FF6B6B", "FFFFFF", true, "FFF0F0", true, true, true, true, 11, 22f, 0f)
                    "minimal" -> Preset(null, null, false, null, false, true, false, false, 11, 20f, 0f)
                    "dark" -> Preset("2F3640", "FFFFFF", true, "F5F6FA", true, true, true, true, 11, 22f, 0f)
                    "green" -> Preset("2E8B57", "FFFFFF", true, "E8F5E9", true, true, true, true, 11, 22f, 0f)
                    "orange" -> Preset("E67E22", "FFFFFF", true, "FDF2E9", true, true, true, true, 11, 22f, 0f)
                    else -> Preset("D9D9D9", null, false, null, false, true, false, false, 11, 20f, 0f)
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
     * 按“样式签名”缓存，相同签名返回同一个 CellStyle 对象。
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
            val align = alignOverride ?: when {
                value is Number -> HorizontalAlignment.RIGHT
                value is Boolean -> HorizontalAlignment.CENTER
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

    // ============================================================
    // Word / PPT 样式配置 + 主题色
    // ============================================================

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
