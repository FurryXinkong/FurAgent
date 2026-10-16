package net.xinkong.furagent.desktop.data

/**
 * 桌面端 typealias 集中点。
 *
 * 【冗余清理 Batch 8/9/13】原来这些类型定义在各自文件里，清理后集中到这里，
 * 一览桌面端所有 typealias。未来可以改成直接 import shared，但改动面大，暂保留。
 */

typealias DesktopMessage = net.xinkong.furagent.shared.data.ChatMessage
typealias DesktopSession = net.xinkong.furagent.shared.data.Session
typealias DesktopSessionMeta = net.xinkong.furagent.shared.data.SessionMeta
typealias Workflow = net.xinkong.furagent.shared.data.Workflow
typealias WorkflowStep = net.xinkong.furagent.shared.data.WorkflowStep