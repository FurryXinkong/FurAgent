@file:Suppress("unused")

package net.xinkong.furagent.shared.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * FurAgent 图标体系 v2 —— 极简空心线条（自绘）。
 *
 * 设计语法：
 *  - 24×24 网格，安全区 3..21
 *  - 主笔 2f，圆头圆角（Round cap / Round join）
 *  - 单图标 ≤6 段主笔；实心点仅作语义点缀（≤3 个）
 *  - 与 material-icons 同形，调用点零改动
 *
 * 绘制原语：stroke（空心线）/ dot（实心点）/ ring（圆环）/ rect（圆角矩形）。
 */
object Icons {
    /** 原版 Icons.Default 即 Filled。 */
    object Default {

        val Add: ImageVector get() = _Add
        private val _Add by lazy {
            icon("Filled.Add") {
                stroke {
                    moveTo(12f, 4f); verticalLineTo(20f)
                    moveTo(4f, 12f); horizontalLineTo(20f)
                }
            }
        }

        val AddComment: ImageVector get() = _AddComment
        private val _AddComment by lazy {
            icon("Filled.AddComment") {
                rect(3f, 4.5f, 18f, 11f, 2.5f)
                stroke {
                    moveTo(7f, 15.5f)
                    verticalLineTo(19f)
                    lineTo(11f, 15.5f)
                }
                stroke {
                    moveTo(9.5f, 10f); horizontalLineTo(14.5f)
                    moveTo(12f, 7.5f); verticalLineTo(12.5f)
                }
            }
        }

        val AddPhotoAlternate: ImageVector get() = _AddPhotoAlternate
        private val _AddPhotoAlternate by lazy {
            icon("Filled.AddPhotoAlternate") {
                rect(3f, 4.5f, 13f, 15f, 2f)
                stroke {
                    moveTo(5.5f, 17f); lineTo(8.5f, 13f); lineTo(11.5f, 17f)
                }
                stroke {
                    moveTo(16f, 6.5f); horizontalLineTo(21f)
                    moveTo(18.5f, 4f); verticalLineTo(9f)
                }
            }
        }

        val ArrowDropDown: ImageVector get() = _ArrowDropDown
        private val _ArrowDropDown by lazy {
            icon("Filled.ArrowDropDown") {
                stroke {
                    moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                }
            }
        }

        val Memory: ImageVector get() = _Memory
        private val _Memory by lazy {
            icon("Filled.Memory") {
                rect(4f, 6f, 16f, 12f, 2f)
                stroke {
                    moveTo(8f, 12f); horizontalLineTo(12f)
                    moveTo(14f, 12f); horizontalLineTo(18f)
                }
            }
        }

        val Dashboard: ImageVector get() = _Dashboard
        private val _Dashboard by lazy {
            icon("Filled.Dashboard") {
                rect(3f, 4.5f, 18f, 15f, 2f)
                stroke {
                    moveTo(3f, 10f); horizontalLineTo(21f)
                    moveTo(13f, 10f); verticalLineTo(19.5f)
                }
            }
        }

        val BubbleChart: ImageVector get() = _BubbleChart
        private val _BubbleChart by lazy {
            icon("Filled.BubbleChart") {
                ring(7f, 9f, 3f)
                ring(15.5f, 8f, 2.5f)
                ring(13f, 16f, 4.5f)
            }
        }

        val Build: ImageVector get() = _Build
        private val _Build by lazy {
            icon("Filled.Build") {
                rect(3f, 9f, 18f, 11f, 2f)
                stroke {
                    moveTo(8f, 9f)
                    verticalLineTo(7f)
                    horizontalLineTo(16f)
                    verticalLineTo(9f)
                }
                stroke {
                    moveTo(3f, 14f); horizontalLineTo(21f)
                }
            }
        }

        val CheckCircle: ImageVector get() = _CheckCircle
        private val _CheckCircle by lazy {
            icon("Filled.CheckCircle") {
                ring(12f, 12f, 8.5f)
                stroke {
                    moveTo(7.5f, 12f); lineTo(11f, 15.5f); lineTo(16.5f, 9f)
                }
            }
        }

        val Checklist: ImageVector get() = _Checklist
        private val _Checklist by lazy {
            icon("Filled.Checklist") {
                stroke {
                    moveTo(3f, 7f); lineTo(5.5f, 9.5f); lineTo(9f, 5.5f)
                    moveTo(12f, 7f); horizontalLineTo(21f)
                    moveTo(3f, 17f); lineTo(5.5f, 19.5f); lineTo(9f, 15.5f)
                    moveTo(12f, 17f); horizontalLineTo(21f)
                }
            }
        }

        val ChevronRight: ImageVector get() = _ChevronRight
        private val _ChevronRight by lazy {
            icon("Filled.ChevronRight") {
                stroke {
                    moveTo(9f, 5.5f); lineTo(15.5f, 12f); lineTo(9f, 18.5f)
                }
            }
        }

        val Close: ImageVector get() = _Close
        private val _Close by lazy {
            icon("Filled.Close") {
                stroke {
                    moveTo(6f, 6f); lineTo(18f, 18f)
                    moveTo(18f, 6f); lineTo(6f, 18f)
                }
            }
        }

        val Code: ImageVector get() = _Code
        private val _Code by lazy {
            icon("Filled.Code") {
                stroke {
                    moveTo(8.5f, 7f); lineTo(3f, 12f); lineTo(8.5f, 17f)
                    moveTo(15.5f, 7f); lineTo(21f, 12f); lineTo(15.5f, 17f)
                    moveTo(13.5f, 5f); lineTo(10.5f, 19f)
                }
            }
        }

        val Computer: ImageVector get() = _Computer
        private val _Computer by lazy {
            icon("Filled.Computer") {
                rect(3f, 4.5f, 18f, 11f, 2f)
                stroke {
                    moveTo(9.5f, 15.5f); verticalLineTo(18.5f)
                    moveTo(6.5f, 18.5f); horizontalLineTo(15.5f)
                }
            }
        }

        val ContentCopy: ImageVector get() = _ContentCopy
        private val _ContentCopy by lazy {
            icon("Filled.ContentCopy") {
                rect(9f, 9f, 12f, 12f, 2f)
                stroke {
                    moveTo(15f, 3.5f)
                    horizontalLineTo(5.5f)
                    verticalLineTo(15f)
                }
            }
        }

        val Delete: ImageVector get() = _Delete
        private val _Delete by lazy {
            icon("Filled.Delete") {
                stroke {
                    moveTo(4f, 6.5f); horizontalLineTo(20f)
                    moveTo(9f, 6.5f)
                    verticalLineTo(4.5f)
                    horizontalLineTo(15f)
                    verticalLineTo(6.5f)
                    moveTo(6f, 6.5f)
                    lineTo(7f, 20f)
                    horizontalLineTo(17f)
                    lineTo(18f, 6.5f)
                    moveTo(10f, 10.5f); verticalLineTo(16.5f)
                    moveTo(14f, 10.5f); verticalLineTo(16.5f)
                }
            }
        }

        val DeleteSweep: ImageVector get() = _DeleteSweep
        private val _DeleteSweep by lazy {
            icon("Filled.DeleteSweep") {
                stroke {
                    moveTo(3f, 7f); horizontalLineTo(14f)
                    moveTo(5.5f, 7f)
                    verticalLineTo(4.5f)
                    horizontalLineTo(11.5f)
                    verticalLineTo(7f)
                    moveTo(4.5f, 7f)
                    lineTo(5.3f, 18f)
                    horizontalLineTo(11.7f)
                    lineTo(12.5f, 7f)
                }
                stroke {
                    moveTo(16f, 10f); horizontalLineTo(21f)
                    moveTo(16f, 14f); horizontalLineTo(21f)
                    moveTo(17f, 18f); horizontalLineTo(21f)
                }
            }
        }

        val Description: ImageVector get() = _Description
        private val _Description by lazy {
            icon("Filled.Description") {
                stroke {
                    moveTo(6f, 3f)
                    horizontalLineTo(14f)
                    lineTo(18f, 7f)
                    verticalLineTo(21f)
                    horizontalLineTo(6f)
                    close()
                    moveTo(14f, 3f)
                    verticalLineTo(7f)
                    horizontalLineTo(18f)
                }
                stroke {
                    moveTo(9f, 12.5f); horizontalLineTo(15f)
                    moveTo(9f, 16.5f); horizontalLineTo(15f)
                }
            }
        }

        val Devices: ImageVector get() = _Devices
        private val _Devices by lazy {
            icon("Filled.Devices") {
                rect(3f, 4.5f, 12f, 14f, 1.5f)
                rect(16f, 8f, 5.5f, 12f, 1f)
            }
        }

        val DevicesOther: ImageVector get() = _DevicesOther
        private val _DevicesOther by lazy {
            icon("Filled.DevicesOther") {
                rect(2.5f, 4.5f, 12f, 10f, 1.5f)
                stroke {
                    moveTo(8.5f, 14.5f); verticalLineTo(17.5f)
                    moveTo(6f, 17.5f); horizontalLineTo(11f)
                }
                rect(17f, 10f, 4.5f, 8f, 1f)
            }
        }

        val Download: ImageVector get() = _Download
        private val _Download by lazy {
            icon("Filled.Download") {
                stroke {
                    moveTo(12f, 3.5f); verticalLineTo(15f)
                    moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                    moveTo(4f, 20f); horizontalLineTo(20f)
                }
            }
        }

        val Edit: ImageVector get() = _Edit
        private val _Edit by lazy {
            icon("Filled.Edit") {
                stroke {
                    moveTo(4f, 20f)
                    verticalLineTo(16f)
                    lineTo(16f, 4f)
                    lineTo(20f, 8f)
                    lineTo(8f, 20f)
                    close()
                    moveTo(14f, 6f); lineTo(18f, 10f)
                }
            }
        }

        val Error: ImageVector get() = _Error
        val ErrorOutline: ImageVector get() = _Error
        private val _Error by lazy {
            icon("Filled.Error") {
                ring(12f, 12f, 8.5f)
                stroke {
                    moveTo(12f, 7f); verticalLineTo(13f)
                }
                dot(12f, 16.5f, 1.3f)
            }
        }

        val ExpandLess: ImageVector get() = _ExpandLess
        private val _ExpandLess by lazy {
            icon("Filled.ExpandLess") {
                stroke {
                    moveTo(7f, 15f); lineTo(12f, 9f); lineTo(17f, 15f)
                }
            }
        }

        val ExpandMore: ImageVector get() = _ExpandMore
        private val _ExpandMore by lazy {
            icon("Filled.ExpandMore") {
                stroke {
                    moveTo(7f, 9f); lineTo(12f, 15f); lineTo(17f, 9f)
                }
            }
        }

        val Extension: ImageVector get() = _Extension
        private val _Extension by lazy {
            icon("Filled.Extension") {
                stroke {
                    moveTo(4f, 4f)
                    horizontalLineTo(10f)
                    arcTo(2.5f, 2.5f, 0f, false, true, 14f, 4f)
                    horizontalLineTo(20f)
                    verticalLineTo(10f)
                    arcTo(2.5f, 2.5f, 0f, false, true, 20f, 14f)
                    verticalLineTo(20f)
                    horizontalLineTo(4f)
                    close()
                }
            }
        }

        val Favorite: ImageVector get() = _Favorite
        private val _Favorite by lazy {
            icon("Filled.Favorite") {
                stroke {
                    moveTo(12f, 20f)
                    quadTo(3f, 13f, 3f, 8f)
                    quadTo(3f, 4f, 7f, 4f)
                    quadTo(10f, 4f, 12f, 7f)
                    quadTo(14f, 4f, 17f, 4f)
                    quadTo(21f, 4f, 21f, 8f)
                    quadTo(21f, 13f, 12f, 20f)
                    close()
                }
            }
        }

        val FileOpen: ImageVector get() = _FileOpen
        private val _FileOpen by lazy {
            icon("Filled.FileOpen") {
                stroke {
                    moveTo(6f, 4f)
                    horizontalLineTo(14f)
                    lineTo(18f, 8f)
                    verticalLineTo(20f)
                    horizontalLineTo(6f)
                    close()
                    moveTo(14f, 4f)
                    verticalLineTo(8f)
                    horizontalLineTo(18f)
                }
                stroke {
                    moveTo(10f, 20f); horizontalLineTo(20f)
                }
            }
        }

        val Folder: ImageVector get() = _Folder
        private val _Folder by lazy {
            icon("Filled.Folder") {
                stroke {
                    moveTo(3f, 6.5f)
                    horizontalLineTo(9f)
                    lineTo(11f, 8.5f)
                    horizontalLineTo(21f)
                    verticalLineTo(19f)
                    horizontalLineTo(3f)
                    close()
                }
            }
        }

        val FolderOpen: ImageVector get() = _FolderOpen
        private val _FolderOpen by lazy {
            icon("Filled.FolderOpen") {
                stroke {
                    moveTo(3f, 8.5f)
                    horizontalLineTo(21f)
                    verticalLineTo(19f)
                    horizontalLineTo(3f)
                    close()
                }
                stroke {
                    moveTo(3f, 8.5f)
                    verticalLineTo(5f)
                    horizontalLineTo(9f)
                    lineTo(11f, 7f)
                    horizontalLineTo(19f)
                }
            }
        }

        val FolderZip: ImageVector get() = _FolderZip
        private val _FolderZip by lazy {
            icon("Filled.FolderZip") {
                stroke {
                    moveTo(3f, 6.5f)
                    horizontalLineTo(9f)
                    lineTo(11f, 8.5f)
                    horizontalLineTo(21f)
                    verticalLineTo(19f)
                    horizontalLineTo(3f)
                    close()
                }
                stroke {
                    moveTo(12f, 11f); verticalLineTo(16.5f)
                    moveTo(10f, 12.5f); horizontalLineTo(14f)
                    moveTo(10f, 15f); horizontalLineTo(14f)
                }
            }
        }

        val Forum: ImageVector get() = _Forum
        private val _Forum by lazy {
            icon("Filled.Forum") {
                rect(3f, 4f, 13f, 9f, 2f)
                stroke {
                    moveTo(6f, 13f)
                    verticalLineTo(16.5f)
                    lineTo(10f, 13f)
                }
                rect(12f, 11f, 9f, 7f, 2f)
            }
        }

        val History: ImageVector get() = _History
        private val _History by lazy {
            icon("Filled.History") {
                ring(12f, 12f, 8f)
                stroke {
                    moveTo(12f, 7.5f); verticalLineTo(12f)
                    moveTo(12f, 12f); lineTo(15.5f, 13.5f)
                }
            }
        }

        val Image: ImageVector get() = _Image
        private val _Image by lazy {
            icon("Filled.Image") {
                rect(3f, 4.5f, 18f, 15f, 2f)
                stroke {
                    moveTo(5.5f, 17f); lineTo(9.5f, 12f); lineTo(13.5f, 17f)
                }
                dot(16f, 9f, 1.5f)
            }
        }

        val Info: ImageVector get() = _Info
        private val _Info by lazy {
            icon("Filled.Info") {
                ring(12f, 12f, 8.5f)
                stroke {
                    moveTo(12f, 11f); verticalLineTo(16.5f)
                }
                dot(12f, 7.5f, 1.3f)
            }
        }

        val KeyboardArrowDown: ImageVector get() = _KeyboardArrowDown
        private val _KeyboardArrowDown by lazy {
            icon("Filled.KeyboardArrowDown") {
                stroke {
                    moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                }
            }
        }

        val KeyboardArrowUp: ImageVector get() = _KeyboardArrowUp
        private val _KeyboardArrowUp by lazy {
            icon("Filled.KeyboardArrowUp") {
                stroke {
                    moveTo(7f, 15f); lineTo(12f, 9f); lineTo(17f, 15f)
                }
            }
        }

        val LocationOn: ImageVector get() = _LocationOn
        private val _LocationOn by lazy {
            icon("Filled.LocationOn") {
                stroke {
                    moveTo(12f, 20.5f); lineTo(6f, 11.5f)
                    arcTo(6f, 6f, 0f, true, true, 18f, 11.5f)
                    close()
                }
                dot(12f, 9.5f, 1.5f)
            }
        }

        val Loop: ImageVector get() = _Loop
        private val _Loop by lazy {
            icon("Filled.Loop") {
                stroke {
                    moveTo(6f, 8f); horizontalLineTo(17f)
                    moveTo(15f, 5.5f); lineTo(17.5f, 8f); lineTo(15f, 10.5f)
                    moveTo(18f, 16f); horizontalLineTo(7f)
                    moveTo(9f, 13.5f); lineTo(6.5f, 16f); lineTo(9f, 18.5f)
                }
            }
        }

        val Menu: ImageVector get() = _Menu
        private val _Menu by lazy {
            icon("Filled.Menu") {
                stroke {
                    moveTo(4f, 7f); horizontalLineTo(20f)
                    moveTo(4f, 12f); horizontalLineTo(20f)
                    moveTo(4f, 17f); horizontalLineTo(20f)
                }
            }
        }

        val Mic: ImageVector get() = _Mic
        private val _Mic by lazy {
            icon("Filled.Mic") {
                rect(9.5f, 3f, 5f, 10f, 2.5f)
                stroke {
                    moveTo(6f, 11f)
                    quadTo(6f, 17f, 12f, 17f)
                    quadTo(18f, 17f, 18f, 11f)
                    moveTo(12f, 17f); verticalLineTo(20.5f)
                    moveTo(9f, 20.5f); horizontalLineTo(15f)
                }
            }
        }

        val MoreVert: ImageVector get() = _MoreVert
        private val _MoreVert by lazy {
            icon("Filled.MoreVert") {
                dot(12f, 6f, 1.5f)
                dot(12f, 12f, 1.5f)
                dot(12f, 18f, 1.5f)
            }
        }

        val Movie: ImageVector get() = _Movie
        private val _Movie by lazy {
            icon("Filled.Movie") {
                rect(3f, 5f, 18f, 14f, 2f)
                stroke {
                    moveTo(10f, 9f); lineTo(15.5f, 12f); lineTo(10f, 15f); close()
                }
            }
        }

        val MusicNote: ImageVector get() = _MusicNote
        private val _MusicNote by lazy {
            icon("Filled.MusicNote") {
                stroke {
                    moveTo(9f, 17f); verticalLineTo(5f)
                    lineTo(17f, 3f); verticalLineTo(14.5f)
                    moveTo(9f, 9.5f); horizontalLineTo(17f)
                }
                dot(6.5f, 17.5f, 2.5f)
                dot(14.5f, 15f, 2.5f)
            }
        }

        val Notifications: ImageVector get() = _Notifications
        private val _Notifications by lazy {
            icon("Filled.Notifications") {
                stroke {
                    moveTo(6f, 16.5f)
                    quadTo(6.3f, 8f, 12f, 8f)
                    quadTo(17.7f, 8f, 18f, 16.5f)
                    close()
                    moveTo(9.5f, 19f)
                    quadTo(12f, 21.5f, 14.5f, 19f)
                }
                dot(12f, 5.5f, 1.5f)
            }
        }

        val NotificationsActive: ImageVector get() = _NotificationsActive
        private val _NotificationsActive by lazy {
            icon("Filled.NotificationsActive") {
                stroke {
                    moveTo(6f, 16.5f)
                    quadTo(6.3f, 8f, 12f, 8f)
                    quadTo(17.7f, 8f, 18f, 16.5f)
                    close()
                    moveTo(9.5f, 19f)
                    quadTo(12f, 21.5f, 14.5f, 19f)
                    moveTo(3.5f, 7.5f); lineTo(2f, 5.5f)
                    moveTo(20.5f, 7.5f); lineTo(22f, 5.5f)
                }
                dot(12f, 5.5f, 1.5f)
            }
        }

        val OpenInFull: ImageVector get() = _OpenInFull
        private val _OpenInFull by lazy {
            icon("Filled.OpenInFull") {
                stroke {
                    moveTo(9.5f, 9.5f); lineTo(4f, 4f)
                    moveTo(4f, 8.5f); verticalLineTo(4f); horizontalLineTo(8.5f)
                    moveTo(14.5f, 14.5f); lineTo(20f, 20f)
                    moveTo(15.5f, 20f); horizontalLineTo(20f); verticalLineTo(15.5f)
                }
            }
        }

        val PictureInPicture: ImageVector get() = _PictureInPicture
        private val _PictureInPicture by lazy {
            icon("Filled.PictureInPicture") {
                stroke {
                    moveTo(3f, 17f)
                    verticalLineTo(4.5f)
                    horizontalLineTo(21f)
                    verticalLineTo(9f)
                }
                rect(11f, 11f, 10f, 9f, 1f)
            }
        }

        val PlayArrow: ImageVector get() = _PlayArrow
        private val _PlayArrow by lazy {
            icon("Filled.PlayArrow") {
                stroke {
                    moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
                }
            }
        }

        val PowerSettingsNew: ImageVector get() = _PowerSettingsNew
        private val _PowerSettingsNew by lazy {
            icon("Filled.PowerSettingsNew") {
                stroke {
                    moveTo(7f, 6f)
                    quadTo(7f, 17f, 12f, 17f)
                    quadTo(17f, 17f, 17f, 6f)
                    moveTo(12f, 3.5f); verticalLineTo(11.5f)
                }
            }
        }

        val Psychology: ImageVector get() = _Psychology
        private val _Psychology by lazy {
            icon("Filled.Psychology") {
                ring(12f, 9.5f, 6f)
                stroke {
                    moveTo(10f, 15.3f); horizontalLineTo(14f)
                    moveTo(9.5f, 18f); horizontalLineTo(14.5f)
                }
            }
        }

        val RecordVoiceOver: ImageVector get() = _RecordVoiceOver
        private val _RecordVoiceOver by lazy {
            icon("Filled.RecordVoiceOver") {
                ring(8.5f, 8f, 3.5f)
                stroke {
                    moveTo(3f, 19f)
                    quadTo(3.5f, 13.5f, 8.5f, 13f)
                    quadTo(13.5f, 13.5f, 14f, 19f)
                    moveTo(16.5f, 9f)
                    quadTo(18.5f, 12f, 16.5f, 15f)
                    moveTo(19.5f, 7f)
                    quadTo(21.5f, 12f, 19.5f, 17f)
                }
            }
        }

        val Refresh: ImageVector get() = _Refresh
        private val _Refresh by lazy {
            icon("Filled.Refresh") {
                stroke {
                    moveTo(17.3f, 6.7f)
                    arcTo(7.5f, 7.5f, 0f, true, true, 6.7f, 6.7f)
                    moveTo(4.2f, 8.5f); lineTo(6.7f, 6.7f); lineTo(7.5f, 3.6f)
                }
            }
        }

        val RestartAlt: ImageVector get() = _RestartAlt
        private val _RestartAlt by lazy {
            icon("Filled.RestartAlt") {
                stroke {
                    moveTo(6.7f, 6.7f)
                    arcTo(7.5f, 7.5f, 0f, true, true, 17.3f, 6.7f)
                    moveTo(14.5f, 5.5f); lineTo(17.3f, 6.7f); lineTo(19.3f, 4.2f)
                }
            }
        }

        val Schedule: ImageVector get() = _Schedule
        private val _Schedule by lazy {
            icon("Filled.Schedule") {
                ring(12f, 12f, 8f)
                stroke {
                    moveTo(12f, 7.5f); verticalLineTo(12f)
                    moveTo(12f, 12f); lineTo(15.5f, 13.5f)
                }
            }
        }

        val Search: ImageVector get() = _Search
        private val _Search by lazy {
            icon("Filled.Search") {
                ring(10.5f, 10.5f, 6.5f)
                stroke {
                    moveTo(15.5f, 15.5f); lineTo(20f, 20f)
                }
            }
        }

        val SelectAll: ImageVector get() = _SelectAll
        private val _SelectAll by lazy {
            icon("Filled.SelectAll") {
                rect(3.5f, 4.5f, 17f, 15f, 2f)
                rect(8f, 9f, 8f, 6f, 1f)
            }
        }

        val Settings: ImageVector get() = _Settings
        private val _Settings by lazy {
            icon("Filled.Settings") {
                ring(12f, 12f, 7f)
                ring(12f, 12f, 2.5f)
                stroke {
                    moveTo(12f, 5f); verticalLineTo(3f)
                    moveTo(12f, 19f); verticalLineTo(21f)
                    moveTo(5f, 12f); horizontalLineTo(3f)
                    moveTo(19f, 12f); horizontalLineTo(21f)
                }
            }
        }

        val Share: ImageVector get() = _Share
        private val _Share by lazy {
            icon("Filled.Share") {
                stroke {
                    moveTo(7.7f, 16.4f); lineTo(16.3f, 7.6f)
                    moveTo(7.7f, 7.6f); lineTo(16.3f, 16.4f)
                }
                dot(6f, 18f, 2f)
                dot(18f, 6f, 2f)
                dot(18f, 18f, 2f)
            }
        }

        val Smartphone: ImageVector get() = _Smartphone
        private val _Smartphone by lazy {
            icon("Filled.Smartphone") {
                rect(7f, 3f, 10f, 18f, 2f)
                dot(12f, 18f, 0.9f)
            }
        }

        val Sort: ImageVector get() = _Sort
        private val _Sort by lazy {
            icon("Filled.Sort") {
                stroke {
                    moveTo(7f, 4f); verticalLineTo(17f)
                    moveTo(4.5f, 6.5f); lineTo(7f, 4f); lineTo(9.5f, 6.5f)
                    moveTo(17f, 7f); verticalLineTo(20f)
                    moveTo(14.5f, 17.5f); lineTo(17f, 20f); lineTo(19.5f, 17.5f)
                }
            }
        }

        val Stop: ImageVector get() = _Stop
        private val _Stop by lazy {
            icon("Filled.Stop") {
                rect(6f, 6f, 12f, 12f, 2f)
            }
        }

        val Storage: ImageVector get() = _Storage
        private val _Storage by lazy {
            icon("Filled.Storage") {
                stroke {
                    moveTo(4f, 6.5f)
                    arcTo(8f, 3f, 0f, false, true, 20f, 6.5f)
                    verticalLineTo(17.5f)
                    arcTo(8f, 3f, 0f, false, false, 4f, 17.5f)
                    close()
                }
                stroke {
                    moveTo(4f, 12f)
                    arcTo(8f, 3f, 0f, false, false, 20f, 12f)
                }
            }
        }

        val Terminal: ImageVector get() = _Terminal
        private val _Terminal by lazy {
            icon("Filled.Terminal") {
                rect(3f, 4.5f, 18f, 15f, 2f)
                stroke {
                    moveTo(7f, 9f); lineTo(10.5f, 12f); lineTo(7f, 15f)
                    moveTo(13f, 15f); horizontalLineTo(17f)
                }
            }
        }

        val TouchApp: ImageVector get() = _TouchApp
        private val _TouchApp by lazy {
            icon("Filled.TouchApp") {
                stroke {
                    moveTo(8f, 4.5f)
                    verticalLineTo(17f)
                    lineTo(11.2f, 14.2f)
                    lineTo(13f, 19f)
                    lineTo(15.2f, 18.2f)
                    lineTo(13.4f, 13.5f)
                    horizontalLineTo(16.8f)
                    close()
                }
                stroke {
                    moveTo(7f, 21f)
                    quadTo(12f, 18.5f, 17f, 21f)
                }
            }
        }

        val Tune: ImageVector get() = _Tune
        private val _Tune by lazy {
            icon("Filled.Tune") {
                stroke {
                    moveTo(3f, 6.5f); horizontalLineTo(21f)
                    moveTo(3f, 12f); horizontalLineTo(21f)
                    moveTo(3f, 17.5f); horizontalLineTo(21f)
                    moveTo(15f, 4f); verticalLineTo(9f)
                    moveTo(9f, 9.5f); verticalLineTo(14.5f)
                    moveTo(17f, 15f); verticalLineTo(20f)
                }
            }
        }

        val Undo: ImageVector get() = _Undo
        private val _Undo by lazy {
            icon("Filled.Undo") {
                stroke {
                    moveTo(19f, 7f)
                    quadTo(7f, 7f, 7f, 16f)
                    moveTo(16f, 4f); lineTo(19f, 7f); lineTo(19f, 10.5f)
                }
            }
        }

        val Upload: ImageVector get() = _Upload
        private val _Upload by lazy {
            icon("Filled.Upload") {
                stroke {
                    moveTo(12f, 15.5f); verticalLineTo(4.5f)
                    moveTo(7f, 9f); lineTo(12f, 4.5f); lineTo(17f, 9f)
                    moveTo(4f, 20f); horizontalLineTo(20f)
                }
            }
        }

        val VerticalAlignBottom: ImageVector get() = _VerticalAlignBottom
        private val _VerticalAlignBottom by lazy {
            icon("Filled.VerticalAlignBottom") {
                stroke {
                    moveTo(12f, 4f); verticalLineTo(17.5f)
                    moveTo(7f, 12f); lineTo(12f, 17.5f); lineTo(17f, 12f)
                    moveTo(4f, 21f); horizontalLineTo(20f)
                }
            }
        }

        val Warning: ImageVector get() = _Warning
        private val _Warning by lazy {
            icon("Filled.Warning") {
                stroke {
                    moveTo(12f, 3.5f)
                    lineTo(21.5f, 20f)
                    horizontalLineTo(2.5f)
                    close()
                    moveTo(12f, 10f); verticalLineTo(14.5f)
                }
                dot(12f, 17.3f, 1.3f)
            }
        }

        val Wifi: ImageVector get() = _Wifi
        private val _Wifi by lazy {
            icon("Filled.Wifi") {
                stroke {
                    moveTo(5f, 11.5f)
                    quadTo(12f, 18.5f, 19f, 11.5f)
                    moveTo(2.5f, 8f)
                    quadTo(12f, 17.5f, 21.5f, 8f)
                }
                dot(12f, 18.5f, 1.5f)
            }
        }
    }

    /** material 的 AutoMirrored.Filled 同形入口。 */
    object AutoMirrored {
        object Filled {

            val ArrowBack: ImageVector get() = _ArrowBack
            private val _ArrowBack by lazy {
                icon("AutoMirrored.Filled.ArrowBack") {
                    stroke {
                        moveTo(20f, 12f); horizontalLineTo(4f)
                        moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f)
                    }
                }
            }

            val CallSplit: ImageVector get() = _CallSplit
            private val _CallSplit by lazy {
                icon("AutoMirrored.Filled.CallSplit") {
                    stroke {
                        moveTo(6f, 4.5f); lineTo(12.5f, 11f)
                        moveTo(12.5f, 11f); lineTo(18.5f, 17f)
                        moveTo(12.5f, 11f); lineTo(8f, 18f)
                    }
                }
            }

            val Help: ImageVector get() = _Help
            private val _Help by lazy {
                icon("AutoMirrored.Filled.Help") {
                    stroke {
                        moveTo(8.5f, 9f)
                        quadTo(9f, 5.5f, 12f, 5.5f)
                        quadTo(15f, 5.5f, 15f, 8.5f)
                        quadTo(15f, 11f, 12f, 11.5f)
                        verticalLineTo(14f)
                    }
                    dot(12f, 17.5f, 1.3f)
                }
            }

            val InsertDriveFile: ImageVector get() = _InsertDriveFile
            private val _InsertDriveFile by lazy {
                icon("AutoMirrored.Filled.InsertDriveFile") {
                    stroke {
                        moveTo(6f, 3f)
                        horizontalLineTo(14f)
                        lineTo(18f, 7f)
                        verticalLineTo(21f)
                        horizontalLineTo(6f)
                        close()
                        moveTo(14f, 3f)
                        verticalLineTo(7f)
                        horizontalLineTo(18f)
                    }
                }
            }

            val List: ImageVector get() = _List
            private val _List by lazy {
                icon("AutoMirrored.Filled.List") {
                    stroke {
                        moveTo(9f, 6f); horizontalLineTo(21f)
                        moveTo(9f, 12f); horizontalLineTo(21f)
                        moveTo(9f, 18f); horizontalLineTo(21f)
                    }
                    dot(4.5f, 6f, 1.3f)
                    dot(4.5f, 12f, 1.3f)
                    dot(4.5f, 18f, 1.3f)
                }
            }

            val MenuOpen: ImageVector get() = _MenuOpen
            private val _MenuOpen by lazy {
                icon("AutoMirrored.Filled.MenuOpen") {
                    stroke {
                        moveTo(10f, 6.5f); horizontalLineTo(21f)
                        moveTo(10f, 12f); horizontalLineTo(21f)
                        moveTo(10f, 17.5f); horizontalLineTo(21f)
                        moveTo(7f, 12f); horizontalLineTo(3f)
                        moveTo(5.5f, 9.5f); lineTo(3f, 12f); lineTo(5.5f, 14.5f)
                    }
                }
            }

            val OpenInNew: ImageVector get() = _OpenInNew
            private val _OpenInNew by lazy {
                icon("AutoMirrored.Filled.OpenInNew") {
                    stroke {
                        moveTo(11f, 4.5f)
                        horizontalLineTo(4.5f)
                        verticalLineTo(19.5f)
                        horizontalLineTo(19.5f)
                        verticalLineTo(13f)
                        moveTo(9f, 15f); lineTo(19f, 5f)
                        moveTo(15f, 5f)
                        horizontalLineTo(19f)
                        verticalLineTo(9f)
                    }
                }
            }

            val Send: ImageVector get() = _Send
            private val _Send by lazy {
                icon("AutoMirrored.Filled.Send") {
                    stroke {
                        moveTo(21f, 3f)
                        lineTo(3f, 10.5f)
                        lineTo(10.5f, 13.5f)
                        lineTo(13.5f, 21f)
                        close()
                        moveTo(10.5f, 13.5f); lineTo(21f, 3f)
                    }
                }
            }

            val ViewSidebar: ImageVector get() = _ViewSidebar
            private val _ViewSidebar by lazy {
                icon("AutoMirrored.Filled.ViewSidebar") {
                    rect(3f, 4.5f, 18f, 15f, 2f)
                    stroke {
                        moveTo(9.5f, 4.5f); verticalLineTo(19.5f)
                    }
                }
            }

            val VolumeUp: ImageVector get() = _VolumeUp
            private val _VolumeUp by lazy {
                icon("AutoMirrored.Filled.VolumeUp") {
                    stroke {
                        moveTo(4f, 9.5f)
                        horizontalLineTo(8f)
                        lineTo(13f, 5f)
                        verticalLineTo(19f)
                        lineTo(8f, 14.5f)
                        horizontalLineTo(4f)
                        close()
                    }
                    stroke {
                        moveTo(16f, 9.5f)
                        quadTo(18f, 12f, 16f, 14.5f)
                        moveTo(18.5f, 7f)
                        quadTo(21.5f, 12f, 18.5f, 17f)
                    }
                }
            }
        }
    }
}

// ── 绘制原语：统一笔画语法 ────────────────────────────────

private val Ink = SolidColor(Color.Black)

/** 空心线条主笔：2f 笔宽、圆头圆角。 */
private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) = path(
    stroke = Ink,
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = block,
)

/** 实心点缀（唯一允许的填充，仅用于语义上的"点"）。 */
private fun ImageVector.Builder.dot(cx: Float, cy: Float, r: Float) = path(fill = Ink) {
    moveTo(cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    close()
}

/** 圆环（顺时针两段半弧）。 */
private fun ImageVector.Builder.ring(cx: Float, cy: Float, r: Float) = stroke {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

/** 圆角矩形（顺时针四段弧 + 四条边）。 */
private fun ImageVector.Builder.rect(x: Float, y: Float, w: Float, h: Float, r: Float) = stroke {
    val rr = r.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
    moveTo(x + rr, y)
    horizontalLineTo(x + w - rr)
    arcTo(rr, rr, 0f, false, true, x + w, y + rr)
    verticalLineTo(y + h - rr)
    arcTo(rr, rr, 0f, false, true, x + w - rr, y + h)
    horizontalLineTo(x + rr)
    arcTo(rr, rr, 0f, false, true, x, y + h - rr)
    verticalLineTo(y + rr)
    arcTo(rr, rr, 0f, false, true, x + rr, y)
    close()
}

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()