# furagent_uia.ps1
#
# 【常驻模式 v2】
# 从"启动一次处理一条命令后退出"改成"常驻进程循环处理命令"。
# 调用方启动一次 PowerShell，之后通过 stdin 喂 JSON、从 stdout 读 JSON。
#
# 输入: 每次 stdin 读一行 JSON 指令
# 输出: 每次 stdout 输出一行 JSON 结果（紧凑，无换行）
#
# 【关键】本脚本必须在 STA 线程模式下运行（调用方 ProcessBuilder 需带 -STA）。
#    UIA 的 AutomationElement.RootElement 是 COM 单例，MTA 下访问会失败。
#
# 指令格式（每次一行）:
#   {"op":"dump","maxDepth":20,"maxNodes":300}
#   {"op":"find","text":"登录","maxResults":20}
#   {"op":"action","nodeId":"...","action":"CLICK","text":"","resourceId":"","textForMatch":"","boundsLeft":0,"boundsTop":0}
#   {"op":"gesture","type":"tap","x":100,"y":200,"durationMs":100}
#   {"op":"global","action":"BACK"}
#
# 退出条件: stdin 返回 EOF（父进程关闭 stdin）或非法输入导致 break。

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

# ============================================================
# 一次性初始化：加载 assembly 和 native 代码
# 失败直接输出一行 JSON 然后退出（父进程会当作启动失败处理）
# ============================================================
try {
    Add-Type -AssemblyName UIAutomationClient -ErrorAction Stop
    Add-Type -AssemblyName UIAutomationTypes -ErrorAction Stop
    Add-Type -AssemblyName WindowsBase -ErrorAction Stop
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
} catch {
    @{ ok = $false; error = "load UIA assemblies failed: $($_.Exception.Message)" } |
            ConvertTo-Json -Compress
    [Console]::Out.Flush()
    exit 1
}

$nativeCode = @"
using System;
using System.Runtime.InteropServices;
public class UiaNative {
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int nIndex);
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const int SM_CXSCREEN = 0;
    public const int SM_CYSCREEN = 1;
}
"@
try {
    Add-Type -TypeDefinition $nativeCode -ErrorAction Stop
} catch {
    # 可能已加载过（多次脚本执行时），忽略
}

# ============================================================
# helpers
# ============================================================

function Get-RidString($elem) {
    try {
        $rid = $elem.GetRuntimeId()
        return ($rid -join ".")
    } catch {
        return ""
    }
}

function Get-ControlTypeName($elem) {
    try {
        $ct = $elem.Current.ControlType
        if ($null -eq $ct) { return "" }
        return [string]$ct.ProgrammaticName
    } catch {
        return ""
    }
}

function Get-PatternFlags($elem) {
    $flags = @{
        clickable = $false
        editable = $false
        scrollable = $false
        checkable = $false
        expandable = $false
    }
    try {
        $patterns = $elem.GetSupportedPatterns()
        foreach ($p in $patterns) {
            $name = $p.ProgrammaticName
            if ($name -match 'Invoke|Toggle|SelectionItem|ExpandCollapse') {
                $flags.clickable = $true
            }
            if ($name -match 'ValuePattern') {
                $flags.editable = $true
            }
            if ($name -match 'ScrollPattern') {
                $flags.scrollable = $true
            }
            if ($name -match 'TogglePattern') {
                $flags.checkable = $true
            }
            if ($name -match 'ExpandCollapsePattern') {
                $flags.expandable = $true
            }
        }
    } catch { }
    return $flags
}

# 读节点快照（不含子节点）
function New-Snap($elem, $depth, $parentId) {
    $rid = Get-RidString $elem
    $name = ""
    $aid = ""
    $bounds = @(0, 0, 0, 0)
    $isOffscreen = $true
    $isEnabled = $false
    $hasFocus = $false
    try {
        $name = [string]$elem.Current.Name
        $aid = [string]$elem.Current.AutomationId
        $r = $elem.Current.BoundingRectangle
        $bounds = @([int]$r.Left, [int]$r.Top, [int]$r.Right, [int]$r.Bottom)
        $isOffscreen = [bool]$elem.Current.IsOffscreen
        $isEnabled = [bool]$elem.Current.IsEnabled
        $hasFocus = [bool]$elem.Current.HasKeyboardFocus
    } catch { }
    $flags = Get-PatternFlags $elem
    $selfSignificant = (
    -not [string]::IsNullOrWhiteSpace($name) -or
            -not [string]::IsNullOrWhiteSpace($aid) -or
            $flags.clickable -or $flags.editable -or $flags.scrollable -or
            $flags.checkable -or $flags.expandable
    )
    $isZeroSize = ($bounds[2] - $bounds[0]) -le 0 -and ($bounds[3] - $bounds[1]) -le 0
    return @{
        id = $rid
        parentId = $parentId
        depth = $depth
        className = (Get-ControlTypeName $elem)
        text = $name
        contentDesc = ""
        resourceId = $aid
        bounds = $bounds
        clickable = $flags.clickable
        editable = $flags.editable
        scrollable = $flags.scrollable
        checkable = $flags.checkable
        enabled = $isEnabled
        visible = -not $isOffscreen
        focused = $hasFocus
        selfSignificant = $selfSignificant
        isZeroSize = $isZeroSize
        isOffscreen = $isOffscreen
    }
}

# 拿到"根"元素：优先前台窗口，兜底 RootElement
function Get-TargetRoot {
    try {
        $hwnd = [UiaNative]::GetForegroundWindow()
        if ($hwnd -ne [IntPtr]::Zero) {
            $el = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
            if ($null -ne $el) { return $el }
        }
    } catch { }
    try {
        return [System.Windows.Automation.AutomationElement]::RootElement
    } catch {
        return $null
    }
}

# ============================================================
# op: dump
# ============================================================

function Visit-Node($elem, $depth, $parentId) {
    if ($script:visited -ge 100000) {
        $script:truncated = $true
        return $false
    }
    if ($depth -gt $script:maxDepthRef) {
        $script:truncated = $true
        return $false
    }
    $script:visited++

    $snap = New-Snap $elem $depth $parentId

    if ($snap.isOffscreen -and -not $snap.selfSignificant) { return $false }
    if ($snap.isZeroSize -and -not $snap.selfSignificant) { return $false }

    $childKept = $false
    try {
        $child = $script:walkerRef.GetFirstChild($elem)
        while ($null -ne $child) {
            $r = Visit-Node $child ($depth + 1) $snap.id
            if ($r) { $childKept = $true }
            $child = $script:walkerRef.GetNextSibling($child)
        }
    } catch { }

    if (-not $snap.selfSignificant -and -not $childKept) { return $false }

    if ($script:counter -lt $script:maxNodesRef) {
        $clean = @{
            id = $snap.id
            parentId = $snap.parentId
            depth = $snap.depth
            className = $snap.className
            text = $snap.text
            contentDesc = $snap.contentDesc
            resourceId = $snap.resourceId
            bounds = $snap.bounds
            clickable = $snap.clickable
            editable = $snap.editable
            scrollable = $snap.scrollable
            checkable = $snap.checkable
            enabled = $snap.enabled
            visible = $snap.visible
            focused = $snap.focused
        }
        [void]$script:nodesRef.Add($clean)
        $script:counter++
    } else {
        $script:truncated = $true
    }
    return $true
}

function Do-Dump {
    param($maxDepth, $maxNodes)
    $root = Get-TargetRoot
    if ($null -eq $root) {
        return @{ ok = $false; error = "no root element (foreground window unavailable)" }
    }
    $screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    $nodes = New-Object System.Collections.ArrayList
    $script:counter = 0
    $script:visited = 0
    $script:truncated = $false
    $script:nodesRef = $nodes
    $script:walkerRef = $walker
    $script:maxNodesRef = $maxNodes
    $script:maxDepthRef = $maxDepth

    Visit-Node $root 0 "" | Out-Null

    return @{
        ok = $true
        screenWidth = $screen.Width
        screenHeight = $screen.Height
        totalNodes = $script:counter
        truncated = $script:truncated
        nodes = $script:nodesRef
    }
}

# ============================================================
# op: find
# ============================================================

function Match-Node($snap, $cmd) {
    if (-not [string]::IsNullOrEmpty($cmd.text)) {
        $t = [string]$snap.text
        $q = [string]$cmd.text
        if ($cmd.textExact -eq $true) {
            if ($t -ne $q) { return $false }
        } else {
            if ($t.IndexOf($q, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) { return $false }
        }
    }
    if (-not [string]::IsNullOrEmpty($cmd.regex)) {
        try {
            if ([string]$snap.text -notmatch $cmd.regex) { return $false }
        } catch {
            return $false
        }
    }
    if (-not [string]::IsNullOrEmpty($cmd.className)) {
        if ([string]$snap.className -notmatch $cmd.className) { return $false }
    }
    if (-not [string]::IsNullOrEmpty($cmd.resourceId)) {
        if ([string]$snap.resourceId -notmatch $cmd.resourceId) { return $false }
    }
    if ($cmd.clickable -eq $true -and -not $snap.clickable) { return $false }
    if ($cmd.clickable -eq $false -and $snap.clickable) { return $false }
    if ($cmd.editable -eq $true -and -not $snap.editable) { return $false }
    if ($cmd.editable -eq $false -and $snap.editable) { return $false }
    if ($cmd.scrollable -eq $true -and -not $snap.scrollable) { return $false }
    if ($cmd.scrollable -eq $false -and $snap.scrollable) { return $false }
    if ($cmd.enabled -eq $true -and -not $snap.enabled) { return $false }
    if ($cmd.enabled -eq $false -and $snap.enabled) { return $false }
    return $true
}

function Do-Find {
    param($cmd)
    $root = Get-TargetRoot
    if ($null -eq $root) {
        return @{ ok = $false; error = "no root element (foreground window unavailable)" }
    }
    $maxResults = 50
    if ($cmd.maxResults) { $maxResults = [int]$cmd.maxResults }
    if ($maxResults -lt 1) { $maxResults = 1 }
    if ($maxResults -gt 500) { $maxResults = 500 }

    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    $matches = New-Object System.Collections.ArrayList
    $script:visited = 0
    $script:truncated = $false

    $script:cmdRef = $cmd
    $script:maxResultsRef = $maxResults
    $script:walkerFind = $walker
    $script:matchesRef = $matches

    function Walk($elem, $depth, $parentId) {
        if ($script:visited -ge 30000) {
            $script:truncated = $true
            return
        }
        if ($depth -gt 60) { return }
        if ($script:matchesRef.Count -ge $script:maxResultsRef) { return }
        $script:visited++

        $snap = New-Snap $elem $depth $parentId

        if ($snap.isOffscreen -and -not $snap.selfSignificant) { return }
        if ($snap.isZeroSize -and -not $snap.selfSignificant) { return }

        if (Match-Node $snap $script:cmdRef) {
            $clean = @{
                id = $snap.id
                parentId = $snap.parentId
                depth = $snap.depth
                className = $snap.className
                text = $snap.text
                contentDesc = $snap.contentDesc
                resourceId = $snap.resourceId
                bounds = $snap.bounds
                clickable = $snap.clickable
                editable = $snap.editable
                scrollable = $snap.scrollable
                checkable = $snap.checkable
                enabled = $snap.enabled
                visible = $snap.visible
                focused = $snap.focused
            }
            [void]$script:matchesRef.Add($clean)
            if ($script:matchesRef.Count -ge $script:maxResultsRef) { return }
        }

        try {
            $child = $script:walkerFind.GetFirstChild($elem)
            while ($null -ne $child) {
                Walk $child ($depth + 1) $snap.id
                if ($script:matchesRef.Count -ge $script:maxResultsRef) { return }
                $child = $script:walkerFind.GetNextSibling($child)
            }
        } catch { }
    }

    Walk $root 0 ""

    return @{
        ok = $true
        nodes = $script:matchesRef
        totalNodes = $script:visited
        truncated = $script:truncated
    }
}

# ============================================================
# op: action
# ============================================================

function Find-ElementForAction($cmd) {
    $targetRid = [string]$cmd.nodeId
    $targetResourceId = ""
    if ($null -ne $cmd.resourceId) { $targetResourceId = [string]$cmd.resourceId }
    $targetText = ""
    if ($null -ne $cmd.textForMatch) { $targetText = [string]$cmd.textForMatch }
    $hasBounds = ($null -ne $cmd.boundsLeft -and $null -ne $cmd.boundsTop)
    $bl = if ($hasBounds) { [int]$cmd.boundsLeft } else { 0 }
    $bt = if ($hasBounds) { [int]$cmd.boundsTop } else { 0 }

    $root = Get-TargetRoot
    if ($null -eq $root) { return $null }
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    $stack = New-Object System.Collections.Stack
    $stack.Push($root)
    $iter = 0
    $fallback = $null

    while ($stack.Count -gt 0 -and $iter -lt 100000) {
        $iter++
        $elem = $stack.Pop()
        if ($null -eq $elem) { continue }

        $rid = Get-RidString $elem
        if (-not [string]::IsNullOrEmpty($targetRid) -and $rid -eq $targetRid) {
            return $elem
        }

        if ($null -eq $fallback) {
            try {
                $matched = $false
                if (-not [string]::IsNullOrEmpty($targetResourceId)) {
                    if ([string]$elem.Current.AutomationId -eq $targetResourceId) { $matched = $true }
                }
                if (-not $matched -and -not [string]::IsNullOrEmpty($targetText)) {
                    if ([string]$elem.Current.Name -eq $targetText) { $matched = $true }
                }
                if (-not $matched -and $hasBounds) {
                    $r = $elem.Current.BoundingRectangle
                    if ([math]::Abs([int]$r.Left - $bl) -le 30 -and [math]::Abs([int]$r.Top - $bt) -le 30) {
                        $matched = $true
                    }
                }
                if ($matched) { $fallback = $elem }
            } catch { }
        }

        try {
            $child = $walker.GetFirstChild($elem)
            while ($null -ne $child) {
                $stack.Push($child)
                $child = $walker.GetNextSibling($child)
            }
        } catch {
            continue
        }
    }
    return $fallback
}

function Do-Action {
    param($nodeId, $action, $text, $cmd)
    if ([string]::IsNullOrWhiteSpace($nodeId)) {
        return @{ ok = $false; error = "empty nodeId" }
    }
    $elem = Find-ElementForAction $cmd
    if ($null -eq $elem) {
        return @{ ok = $false; error = "node not found (id may be stale, please re-dump)" }
    }

    $act = ([string]$action).ToUpperInvariant()
    try {
        switch ($act) {
            'CLICK' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
                    $pattern.Invoke()
                    return @{ ok = $true; message = "invoked" }
                }
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pattern)) {
                    $pattern.Select()
                    return @{ ok = $true; message = "selected" }
                }
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pattern)) {
                    $pattern.Toggle()
                    return @{ ok = $true; message = "toggled" }
                }
                return @{ ok = $false; error = "element does not support click patterns" }
            }
            'LONG_CLICK' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
                    $pattern.Invoke()
                    return @{ ok = $true; message = "long_click fallback to click" }
                }
                return @{ ok = $false; error = "element does not support long_click" }
            }
            'SET_TEXT' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
                    $pattern.SetValue([string]$text)
                    return @{ ok = $true; message = "text set" }
                }
                return @{ ok = $false; error = "element does not support SET_TEXT (no ValuePattern)" }
            }
            'CLEAR_TEXT' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
                    $pattern.SetValue("")
                    return @{ ok = $true; message = "text cleared" }
                }
                return @{ ok = $false; error = "element does not support CLEAR_TEXT" }
            }
            'FOCUS' {
                $elem.SetFocus()
                return @{ ok = $true; message = "focused" }
            }
            'CHECK' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pattern)) {
                    $state = $pattern.Current.ToggleState
                    if ($state -ne [System.Windows.Automation.ToggleState]::On) {
                        $pattern.Toggle()
                    }
                    return @{ ok = $true; message = "checked" }
                }
                return @{ ok = $false; error = "element is not toggleable" }
            }
            'UNCHECK' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pattern)) {
                    $state = $pattern.Current.ToggleState
                    if ($state -ne [System.Windows.Automation.ToggleState]::Off) {
                        $pattern.Toggle()
                    }
                    return @{ ok = $true; message = "unchecked" }
                }
                return @{ ok = $false; error = "element is not toggleable" }
            }
            'EXPAND' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$pattern)) {
                    $pattern.Expand()
                    return @{ ok = $true; message = "expanded" }
                }
                return @{ ok = $false; error = "element is not expandable" }
            }
            'COLLAPSE' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$pattern)) {
                    $pattern.Collapse()
                    return @{ ok = $true; message = "collapsed" }
                }
                return @{ ok = $false; error = "element is not collapsible" }
            }
            'SCROLL_FORWARD' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
                    if ($pattern.Current.VerticallyScrollable) {
                        $pattern.Scroll([System.Windows.Automation.ScrollAmount]::NoAmount,
                                [System.Windows.Automation.ScrollAmount]::LargeIncrement)
                    } else {
                        $pattern.Scroll([System.Windows.Automation.ScrollAmount]::LargeIncrement,
                                [System.Windows.Automation.ScrollAmount]::NoAmount)
                    }
                    return @{ ok = $true; message = "scrolled forward" }
                }
                return @{ ok = $false; error = "element is not scrollable" }
            }
            'SCROLL_BACKWARD' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
                    if ($pattern.Current.VerticallyScrollable) {
                        $pattern.Scroll([System.Windows.Automation.ScrollAmount]::NoAmount,
                                [System.Windows.Automation.ScrollAmount]::LargeDecrement)
                    } else {
                        $pattern.Scroll([System.Windows.Automation.ScrollAmount]::LargeDecrement,
                                [System.Windows.Automation.ScrollAmount]::NoAmount)
                    }
                    return @{ ok = $true; message = "scrolled backward" }
                }
                return @{ ok = $false; error = "element is not scrollable" }
            }
            'SCROLL_UP' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
                    $pattern.Scroll([System.Windows.Automation.ScrollAmount]::NoAmount,
                            [System.Windows.Automation.ScrollAmount]::LargeDecrement)
                    return @{ ok = $true; message = "scrolled up" }
                }
                return @{ ok = $false; error = "element is not scrollable" }
            }
            'SCROLL_DOWN' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
                    $pattern.Scroll([System.Windows.Automation.ScrollAmount]::NoAmount,
                            [System.Windows.Automation.ScrollAmount]::LargeIncrement)
                    return @{ ok = $true; message = "scrolled down" }
                }
                return @{ ok = $false; error = "element is not scrollable" }
            }
            'SCROLL_LEFT' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
                    $pattern.Scroll([System.Windows.Automation.ScrollAmount]::LargeDecrement,
                            [System.Windows.Automation.ScrollAmount]::NoAmount)
                    return @{ ok = $true; message = "scrolled left" }
                }
                return @{ ok = $false; error = "element is not scrollable" }
            }
            'SCROLL_RIGHT' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
                    $pattern.Scroll([System.Windows.Automation.ScrollAmount]::LargeIncrement,
                            [System.Windows.Automation.ScrollAmount]::NoAmount)
                    return @{ ok = $true; message = "scrolled right" }
                }
                return @{ ok = $false; error = "element is not scrollable" }
            }
            'SELECT' {
                $pattern = $null
                if ($elem.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pattern)) {
                    $pattern.Select()
                    return @{ ok = $true; message = "selected" }
                }
                return @{ ok = $false; error = "element is not selectable" }
            }
            default {
                return @{ ok = $false; error = "unsupported action: $action" }
            }
        }
    } catch {
        return @{ ok = $false; error = "action failed: $($_.Exception.Message)" }
    }
}

# ============================================================
# op: gesture
# ============================================================

function Do-Gesture {
    param($type, $x, $y, $startX, $startY, $endX, $endY, $durationMs)
    $t = ([string]$type).ToLowerInvariant()
    $dur = [int]$durationMs
    if ($dur -le 0) { $dur = 200 }

    $sw = [UiaNative]::GetSystemMetrics([UiaNative]::SM_CXSCREEN)
    $sh = [UiaNative]::GetSystemMetrics([UiaNative]::SM_CYSCREEN)

    function Clamp-X([int]$v) {
        if ($v -lt 0) { return 0 }
        if ($v -ge $sw) { return $sw - 1 }
        return $v
    }
    function Clamp-Y([int]$v) {
        if ($v -lt 0) { return 0 }
        if ($v -ge $sh) { return $sh - 1 }
        return $v
    }

    switch ($t) {
        'tap' {
            $cx = Clamp-X $x
            $cy = Clamp-Y $y
            if ([UiaNative]::SetCursorPos($cx, $cy) -eq $false) {
                return @{ ok = $false; error = "SetCursorPos failed for ($cx,$cy)" }
            }
            Start-Sleep -Milliseconds 30
            [UiaNative]::mouse_event([UiaNative]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [System.UIntPtr]::Zero)
            Start-Sleep -Milliseconds 40
            [UiaNative]::mouse_event([UiaNative]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [System.UIntPtr]::Zero)
            return @{ ok = $true; message = "tapped at ($cx,$cy)" }
        }
        'long_press' {
            $cx = Clamp-X $x
            $cy = Clamp-Y $y
            if ([UiaNative]::SetCursorPos($cx, $cy) -eq $false) {
                return @{ ok = $false; error = "SetCursorPos failed for ($cx,$cy)" }
            }
            Start-Sleep -Milliseconds 30
            [UiaNative]::mouse_event([UiaNative]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [System.UIntPtr]::Zero)
            Start-Sleep -Milliseconds ([Math]::Max(600, $dur))
            [UiaNative]::mouse_event([UiaNative]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [System.UIntPtr]::Zero)
            return @{ ok = $true; message = "long pressed at ($cx,$cy)" }
        }
        'swipe' {
            $sx = Clamp-X $startX
            $sy = Clamp-Y $startY
            $ex = Clamp-X $endX
            $ey = Clamp-Y $endY
            if ([UiaNative]::SetCursorPos($sx, $sy) -eq $false) {
                return @{ ok = $false; error = "SetCursorPos failed for start ($sx,$sy)" }
            }
            Start-Sleep -Milliseconds 30
            [UiaNative]::mouse_event([UiaNative]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [System.UIntPtr]::Zero)
            $steps = 20
            $stepDelay = [Math]::Max(10, [int]($dur / $steps))
            for ($i = 1; $i -le $steps; $i++) {
                $cx = [int]($sx + ($ex - $sx) * $i / $steps)
                $cy = [int]($sy + ($ey - $sy) * $i / $steps)
                [UiaNative]::SetCursorPos($cx, $cy) | Out-Null
                Start-Sleep -Milliseconds $stepDelay
            }
            [UiaNative]::mouse_event([UiaNative]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [System.UIntPtr]::Zero)
            return @{ ok = $true; message = "swiped from ($sx,$sy) to ($ex,$ey)" }
        }
        default {
            return @{ ok = $false; error = "unsupported gesture type: $type" }
        }
    }
}

# ============================================================
# op: global
# ============================================================

function Do-Global {
    param($action)
    $act = ([string]$action).ToUpperInvariant()

    $VK_LWIN = 0x5B
    $VK_LALT = 0x12
    $VK_TAB = 0x09
    $VK_LEFT = 0x25
    $VK_D = 0x44
    $VK_L = 0x4C
    $VK_N = 0x4E
    $VK_A = 0x41

    function PressCombo($mods, $key) {
        foreach ($m in $mods) {
            [UiaNative]::keybd_event([byte]$m, 0, 0, [System.UIntPtr]::Zero)
            Start-Sleep -Milliseconds 20
        }
        [UiaNative]::keybd_event([byte]$key, 0, 0, [System.UIntPtr]::Zero)
        Start-Sleep -Milliseconds 50
        [UiaNative]::keybd_event([byte]$key, 0, [UiaNative]::KEYEVENTF_KEYUP, [System.UIntPtr]::Zero)
        foreach ($m in $mods) {
            Start-Sleep -Milliseconds 20
            [UiaNative]::keybd_event([byte]$m, 0, [UiaNative]::KEYEVENTF_KEYUP, [System.UIntPtr]::Zero)
        }
    }

    switch ($act) {
        'BACK' {
            PressCombo @($VK_LALT) $VK_LEFT
            return @{ ok = $true; message = "alt+left" }
        }
        'HOME' {
            PressCombo @($VK_LWIN) $VK_D
            return @{ ok = $true; message = "win+d (show desktop)" }
        }
        'RECENTS' {
            PressCombo @($VK_LALT) $VK_TAB
            return @{ ok = $true; message = "alt+tab" }
        }
        'NOTIFICATIONS' {
            PressCombo @($VK_LWIN) $VK_N
            return @{ ok = $true; message = "win+n" }
        }
        'QUICK_SETTINGS' {
            PressCombo @($VK_LWIN) $VK_A
            return @{ ok = $true; message = "win+a" }
        }
        'LOCK_SCREEN' {
            PressCombo @($VK_LWIN) $VK_L
            return @{ ok = $true; message = "win+l" }
        }
        default {
            return @{ ok = $false; error = "unsupported global action: $action" }
        }
    }
}

# ============================================================
# 主循环（常驻）
# ============================================================

# 用 StreamReader 强制 UTF-8 解码 stdin，避免 PowerShell 默认用系统 ANSI 代码页
try {
    $stdinReader = New-Object System.IO.StreamReader(
    [Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
} catch {
    @{ ok = $false; error = "open stdin reader failed: $($_.Exception.Message)" } |
            ConvertTo-Json -Compress
    [Console]::Out.Flush()
    exit 1
}

while ($true) {
    $inputJson = $null
    try {
        $inputJson = $stdinReader.ReadLine()
    } catch {
        break
    }

    # stdin EOF：父进程关闭了我们的 stdin，退出
    if ($null -eq $inputJson) { break }

    # 空行跳过
    if ([string]::IsNullOrWhiteSpace($inputJson)) { continue }

    $cmd = $null
    try {
        $cmd = $inputJson | ConvertFrom-Json
    } catch {
        @{ ok = $false; error = "parse input failed: $($_.Exception.Message)" } |
                ConvertTo-Json -Compress
        [Console]::Out.Flush()
        continue
    }

    $op = [string]$cmd.op
    $result = $null

    try {
        switch ($op) {
            'dump' {
                $maxDepth = if ($cmd.maxDepth) { [int]$cmd.maxDepth } else { 20 }
                $maxNodes = if ($cmd.maxNodes) { [int]$cmd.maxNodes } else { 300 }
                $result = Do-Dump $maxDepth $maxNodes
            }
            'find' {
                $result = Do-Find $cmd
            }
            'action' {
                $result = Do-Action ([string]$cmd.nodeId) ([string]$cmd.action) ([string]$cmd.text) $cmd
            }
            'gesture' {
                $result = Do-Gesture ([string]$cmd.type) `
                    ([int]$cmd.x) ([int]$cmd.y) `
                    ([int]$cmd.startX) ([int]$cmd.startY) `
                    ([int]$cmd.endX) ([int]$cmd.endY) `
                    ([int]$cmd.durationMs)
            }
            'global' {
                $result = Do-Global ([string]$cmd.action)
            }
            default {
                $result = @{ ok = $false; error = "unknown op: $op" }
            }
        }
    } catch {
        $result = @{ ok = $false; error = "dispatch failed: $($_.Exception.Message)" }
    }

    # 输出一行紧凑 JSON，flush 保证父进程能及时收到
    try {
        $result | ConvertTo-Json -Depth 100 -Compress
        [Console]::Out.Flush()
    } catch {
        [Console]::Out.WriteLine('{"ok":false,"error":"json encode failed"}')
        [Console]::Out.Flush()
    }
}