import AppKit
import ApplicationServices
import CryptoKit

final class ConsoleActions {
    private struct Application {
        let id: String
        let name: String
        let url: URL
    }

    private struct Row: Encodable {
        let id: Int
        let name: String
        let running: Bool
        let front: Bool
        let key: String
        let codex: Bool
        let sort: String
        let initials: String
    }

    private struct Reply: Encodable {
        let sequence: UInt32
        let ok: Bool
        let message: String?
        let rows: [Row]?
        let next: Int?
        let generation: Int?
    }

    private var iconData = Data()
    private var iconGeneration = 0
    private var launchErrors: [String: String] = [:]
    private let workspace: NSWorkspace
    private var snapshot: [Application] = []
    private var generation = 0
    private var catalogRevision = ""
    private let hostID: String = {
        let defaults = UserDefaults.standard
        if let saved = defaults.string(forKey: "TrackpadHostID"), UUID(uuidString: saved) != nil { return saved }
        let id = UUID().uuidString
        defaults.set(id, forKey: "TrackpadHostID")
        return id
    }()

    init(workspace: NSWorkspace = .shared) {
        self.workspace = workspace
    }

    func reset() {
        snapshot.removeAll()
        catalogRevision = ""
        iconData.removeAll()
        iconGeneration = 0
    }

    func respond(to packet: TrackpadPacket) -> Data {
        switch packet.kind {
        case .catalogStatus:
            return catalogStatus(packet)
        case .catalogState:
            return catalogState(packet)
        case .listApps:
            return appPage(packet)
        case .appState:
            return appState(packet)
        case .appIcon:
            return appIcon(packet)
        case .iconChunk:
            return iconChunk(packet)
        case .openApp, .quitApp:
            let (ok, message) = changeApplication(packet)
            return messageReply(packet, ok, message)
        case .codexDictation, .codexSend, .codexStop, .codexNew:
            let (ok, message) = controlCodex(packet.kind)
            return messageReply(packet, ok, message)
        default:
            return messageReply(packet, false, "未知控制台指令")
        }
    }

    private func messageReply(_ packet: TrackpadPacket, _ ok: Bool, _ message: String) -> Data {
        encode(Reply(sequence: packet.sequence, ok: ok, message: message, rows: nil, next: nil, generation: nil)) ?? Data()
    }

    private func encode(_ reply: Reply) -> Data? {
        try? JSONEncoder().encode(reply)
    }

    private func prepareCatalog() {
        let running = workspace.runningApplications.filter { $0.activationPolicy == .regular && !$0.isTerminated }
        let updated = installedApplications(running: running)
        let entries = updated.map { [$0.id, $0.name, $0.url.path, iconKey($0)] }
        guard let data = try? JSONSerialization.data(withJSONObject: entries) else { return }
        let revision = SHA256.hash(data: data).prefix(16).map { String(format: "%02x", $0) }.joined()
        if revision != catalogRevision {
            snapshot = updated
            catalogRevision = revision
            generation = (generation % Int(Int16.max)) + 1
            iconData.removeAll()
            iconGeneration = 0
        }
    }

    private func catalogStatus(_ packet: TrackpadPacket) -> Data {
        prepareCatalog()
        let reply: [String: Any] = ["sequence": packet.sequence, "ok": true, "host": hostID,
            "catalog": catalogRevision, "generation": generation, "count": snapshot.count]
        return (try? JSONSerialization.data(withJSONObject: reply)) ?? Data()
    }

    private func catalogState(_ packet: TrackpadPacket) -> Data {
        guard packet.y == generation, packet.x >= 0, packet.x <= snapshot.count else {
            return messageReply(packet, false, "应用列表已失效，请刷新")
        }
        let end = min(snapshot.count, packet.x + 48)
        let runningIDs = Set(workspace.runningApplications.filter { !$0.isTerminated }.compactMap(\.bundleIdentifier))
        let running = (packet.x..<end).filter { runningIDs.contains(snapshot[$0].id) }.map { $0 + 1 }
        let front = workspace.frontmostApplication?.bundleIdentifier
        let frontID = snapshot.firstIndex { $0.id == front }.map { $0 + 1 } ?? 0
        let reply: [String: Any] = ["sequence": packet.sequence, "ok": true, "generation": generation,
            "running": running, "front": frontID, "next": end < snapshot.count ? end : -1]
        return (try? JSONSerialization.data(withJSONObject: reply)) ?? Data()
    }

    private func appPage(_ packet: TrackpadPacket) -> Data {
        let offset = packet.x
        let running = workspace.runningApplications.filter { $0.activationPolicy == .regular && !$0.isTerminated }
        if offset == 0 && packet.y == 0 { prepareCatalog() }
        guard offset >= 0, offset <= snapshot.count, offset == 0 || packet.y == generation else {
            return messageReply(packet, false, "应用列表已失效，请刷新")
        }
        let runningIDs = Set(running.compactMap(\.bundleIdentifier))
        let front = workspace.frontmostApplication?.bundleIdentifier
        var rows: [Row] = []
        var lastPage: Data?
        for index in offset...min(snapshot.count, offset + 8) {
            if index > offset {
                let app = snapshot[index - 1]
                rows.append(Row(id: index, name: app.name, running: runningIDs.contains(app.id), front: app.id == front, key: iconKey(app), codex: app.id == "com.openai.codex", sort: sortName(app.name), initials: searchInitials(app.name)))
            }
            let reply = Reply(sequence: packet.sequence, ok: true, message: nil, rows: rows,
                              next: index < snapshot.count ? index : nil, generation: generation)
            guard let page = encode(reply), page.count <= 480 else { break }
            lastPage = page
        }
        return lastPage ?? messageReply(packet, false, "应用列表无法编码")
    }

    private func sortName(_ name: String) -> String {
        let latin = name.applyingTransform(.toLatin, reverse: false) ?? name
        return String(latin.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .replacingOccurrences(of: " ", with: "").prefix(96))
    }

    private func searchInitials(_ name: String) -> String {
        let latin = name.applyingTransform(.toLatin, reverse: false) ?? name
        let folded = latin.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
        let words = folded.components(separatedBy: CharacterSet.alphanumerics.inverted)
        return String(words.compactMap(\.first).prefix(48))
    }

    private func iconKey(_ app: Application) -> String {
        let version = Bundle(url: app.url)?.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        return SHA256.hash(data: Data((app.id + "\n" + version).utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    private func appState(_ packet: TrackpadPacket) -> Data {
        guard packet.y == generation, snapshot.indices.contains(packet.x - 1) else {
            return messageReply(packet, false, "应用列表已失效，请刷新")
        }
        let app = snapshot[packet.x - 1]
        let running = workspace.runningApplications.contains { $0.bundleIdentifier == app.id && !$0.isTerminated }
        let front = workspace.frontmostApplication?.bundleIdentifier == app.id
        let object: [String: Any] = ["sequence": packet.sequence, "ok": true, "id": packet.x,
            "running": running, "front": front, "generation": generation,
            "error": launchErrors[app.id] ?? ""]
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    private func appIcon(_ packet: TrackpadPacket) -> Data {
        iconData.removeAll()
        guard packet.y == generation, snapshot.indices.contains(packet.x - 1) else {
            return messageReply(packet, false, "应用列表已失效，请刷新")
        }
        let image = workspace.icon(forFile: snapshot[packet.x - 1].url.path)
        guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 48, pixelsHigh: 48,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
            let context = NSGraphicsContext(bitmapImageRep: bitmap) else {
            return messageReply(packet, false, "图标暂不可用")
        }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        NSColor(calibratedRed: 24.0 / 255, green: 33.0 / 255, blue: 48.0 / 255, alpha: 1).setFill()
        NSRect(x: 0, y: 0, width: 48, height: 48).fill()
        image.draw(in: NSRect(x: 0, y: 0, width: 48, height: 48))
        NSGraphicsContext.restoreGraphicsState()
        guard let data = bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.6]), data.count <= 32767 else {
            return messageReply(packet, false, "图标暂不可用")
        }
        iconData = data
        iconGeneration = generation
        return iconPage(packet, offset: 0)
    }

    private func iconChunk(_ packet: TrackpadPacket) -> Data {
        guard packet.y == generation, iconGeneration == generation, !iconData.isEmpty,
              packet.x >= 0, packet.x < iconData.count else {
            return messageReply(packet, false, "图标已失效")
        }
        return iconPage(packet, offset: packet.x)
    }

    private func iconPage(_ packet: TrackpadPacket, offset: Int) -> Data {
        let end = min(iconData.count, offset + 240)
        let object: [String: Any] = ["sequence": packet.sequence, "ok": true,
            "icon": iconData.subdata(in: offset..<end).base64EncodedString(),
            "next": end < iconData.count ? end : -1, "generation": generation]
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    private func installedApplications(running: [NSRunningApplication]) -> [Application] {
        var apps: [String: Application] = [:]
        let roots = [
            FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications"),
            URL(fileURLWithPath: "/Applications"),
            URL(fileURLWithPath: "/System/Applications")
        ]
        for root in roots {
            guard let entries = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil,
                                                               options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { continue }
            for case let url as URL in entries {
                guard url.pathExtension.lowercased() == "app" else { continue }
                entries.skipDescendants()
                guard let bundle = Bundle(url: url), let id = bundle.bundleIdentifier,
                      bundle.object(forInfoDictionaryKey: "LSBackgroundOnly") as? Bool != true,
                      bundle.object(forInfoDictionaryKey: "LSUIElement") as? Bool != true,
                      apps[id] == nil else { continue }
                let name = FileManager.default.displayName(atPath: url.path)
                apps[id] = application(id: id, name: name, url: url)
            }
        }
        for process in running {
            guard let id = process.bundleIdentifier, let url = process.bundleURL else { continue }
            apps[id] = application(id: id, name: process.localizedName ?? url.lastPathComponent, url: url)
        }
        apps.removeValue(forKey: Bundle.main.bundleIdentifier ?? "")
        return Array(apps.values.sorted {
            let comparison = $0.name.localizedStandardCompare($1.name)
            return comparison == .orderedSame ? $0.id < $1.id : comparison == .orderedAscending
        }.prefix(Int(Int16.max)))
    }

    private func application(id: String, name: String, url: URL) -> Application {
        let title = name.hasSuffix(".app") ? String(name.dropLast(4)) : name
        // Limit UTF-8 bytes and keep a complete character at the page boundary.
        var displayName = ""
        for character in title {
            let text = String(character)
            if text.rangeOfCharacter(from: .controlCharacters) != nil { continue }
            if displayName.utf8.count + text.utf8.count > 96 { break }
            displayName.append(character)
        }
        return Application(id: id, name: displayName.isEmpty ? "应用" : displayName, url: url)
    }

    private func changeApplication(_ packet: TrackpadPacket) -> (Bool, String) {
        let index = packet.x - 1
        guard packet.y == generation, snapshot.indices.contains(index) else { return (false, "应用列表已失效，请刷新") }
        let app = snapshot[index]
        let running = NSRunningApplication.runningApplications(withBundleIdentifier: app.id)
            .filter { $0.activationPolicy == .regular && !$0.isTerminated }
        if packet.kind == .quitApp {
            guard let process = running.first else { return (false, "应用已退出，请刷新列表") }
            if app.id == "com.apple.finder" { return (false, "Finder 不提供普通退出操作") }
            guard process.terminate() else { return (false, "Mac 拒绝了退出请求") }
            return (true, "已请求退出，若有保存提示请在 Mac 处理")
        }
        guard Bundle(url: app.url)?.bundleIdentifier == app.id else {
            return (false, "应用路径已变化，请刷新列表")
        }
        launchErrors.removeValue(forKey: app.id)
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = true
        workspace.openApplication(at: app.url, configuration: configuration) { [weak self] _, error in
            DispatchQueue.main.async {
                if let error { self?.launchErrors[app.id] = String(error.localizedDescription.prefix(48)) }
            }
        }
        return (true, "正在打开或切换应用…")
    }

    private func controlCodex(_ kind: TrackpadPacket.Kind) -> (Bool, String) {
        let codexID = "com.openai.codex"
        if kind == .codexNew {
            guard let url = URL(string: "codex://threads/new"),
                  workspace.urlForApplication(toOpen: url).flatMap({ Bundle(url: $0)?.bundleIdentifier }) == codexID,
                  workspace.open(url) else {
                return (false, "Mac 未找到可打开新对话的 Codex")
            }
            return (true, "已请求新建 Codex 对话")
        }

        guard AXIsProcessTrusted() else { return (false, "请先在 Mac 授权测试接收端的辅助功能") }
        guard let process = workspace.frontmostApplication, process.bundleIdentifier == codexID else {
            return (false, "请先把 Codex 切到 Mac 前台")
        }
        let pid = process.processIdentifier
        let app = AXUIElementCreateApplication(pid)
        AXUIElementSetMessagingTimeout(app, 0.15)
        guard let window = element(app, kAXFocusedWindowAttribute),
              value(window, kAXRoleAttribute) as? String == kAXWindowRole,
              belongsTo(window, pid: pid) else { return (false, "请打开 Codex 对话窗口") }

        switch kind {
        case .codexDictation:
            if let button = composerButton(in: window, names: ["stop dictation", "finish dictation", "停止听写", "结束听写", "停止聽寫", "結束聽寫", "停止口述"], requireComposer: false) {
                return click(button, window: window, pid: pid, message: "已点击结束听写，转写结果会留在草稿")
            }
            guard let button = composerButton(in: window, names: ["dictate", "start dictation", "voice dictation", "听写", "聽寫", "开始听写", "開始聽寫"]) else {
                return (false, "当前对话没有可用的听写按钮")
            }
            return click(button, window: window, pid: pid, message: "已点击听写，请对着 Mac 的麦克风说话")
        case .codexSend:
            guard let button = composerButton(in: window, names: ["send", "send message", "send prompt", "send-button", "composer-send-button", "发送", "傳送", "发送消息", "傳送訊息", "发送提示"]) else {
                return (false, "当前没有可用的发送按钮，请检查草稿和对话状态")
            }
            return click(button, window: window, pid: pid, message: "已点击发送当前草稿")
        case .codexStop:
            guard let button = composerButton(in: window, names: ["stop", "stop generating", "stop response", "stop-button", "停止", "停止生成", "停止回覆"]) else {
                return (false, "当前没有可停止的回复")
            }
            return click(button, window: window, pid: pid, message: "已点击停止当前回复")
        default:
            return (false, "未知 Codex 指令")
        }
    }

    private func value(_ node: AXUIElement, _ attribute: String) -> CFTypeRef? {
        var result: CFTypeRef?
        return AXUIElementCopyAttributeValue(node, attribute as CFString, &result) == .success ? result : nil
    }

    private func element(_ node: AXUIElement, _ attribute: String) -> AXUIElement? {
        guard let item = value(node, attribute), CFGetTypeID(item) == AXUIElementGetTypeID() else { return nil }
        return unsafeBitCast(item, to: AXUIElement.self)
    }

    private func children(_ node: AXUIElement) -> [AXUIElement] {
        value(node, kAXChildrenAttribute) as? [AXUIElement] ?? []
    }

    private func controlNames(_ node: AXUIElement) -> [String] {
        [kAXTitleAttribute, kAXDescriptionAttribute, kAXHelpAttribute, "AXLabel", kAXIdentifierAttribute]
            .compactMap { value(node, $0) as? String }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
    }

    private func find(in window: AXUIElement, match: (AXUIElement, String) -> Bool) -> AXUIElement? {
        var nodes = [window]
        let deadline = ProcessInfo.processInfo.systemUptime + 1.3
        var remaining = 1600
        while let node = nodes.popLast(), remaining > 0, ProcessInfo.processInfo.systemUptime < deadline {
            remaining -= 1
            let role = value(node, kAXRoleAttribute) as? String ?? ""
            if match(node, role) { return node }
            if [kAXApplicationRole, kAXMenuBarRole, kAXMenuRole].contains(role) { continue }
            nodes.append(contentsOf: children(node))
        }
        return nil
    }

    private func composerButton(in window: AXUIElement, names: Set<String>, requireComposer: Bool = true) -> AXUIElement? {
        find(in: window) { node, role in
            guard role == kAXButtonRole, value(node, kAXEnabledAttribute) as? Bool != false else { return false }
            return controlNames(node).contains { label in
                names.contains(label) || names.contains { label.hasPrefix($0 + " (") || label.hasPrefix($0 + "（") }
            } && (!requireComposer || inComposer(node))
        }
    }

    private func inComposer(_ button: AXUIElement) -> Bool {
        let permissionNames: Set<String> = ["change permissions", "更改权限", "更改權限", "變更權限"]
        var current = button
        for _ in 0..<3 {
            guard let parent = element(current, kAXParentAttribute) else { return false }
            let siblings = children(parent)
            guard siblings.count <= 18 else { return false }
            // The question panel has a separate text area and its own Send button.
            if siblings.contains(where: { value($0, kAXRoleAttribute) as? String == kAXTextAreaRole }),
               siblings.contains(where: {
                   value($0, kAXRoleAttribute) as? String == kAXPopUpButtonRole
                       && !permissionNames.isDisjoint(with: controlNames($0))
               }) { return true }
            current = parent
        }
        return false
    }

    private func belongsTo(_ node: AXUIElement, pid: pid_t) -> Bool {
        var actual: pid_t = 0
        return AXUIElementGetPid(node, &actual) == .success && actual == pid
    }

    private func click(_ button: AXUIElement, window: AXUIElement, pid: pid_t, message: String) -> (Bool, String) {
        guard let point = visiblePoint(for: button) else { return (false, "Codex 按钮被遮挡或已变化，请重试") }
        guard workspace.frontmostApplication?.processIdentifier == pid,
              let focused = element(AXUIElementCreateApplication(pid), kAXFocusedWindowAttribute),
              CFEqual(focused, window), belongsTo(button, pid: pid) else { return (false, "Codex 前台窗口已变化") }
        guard value(button, kAXEnabledAttribute) as? Bool != false,
              !CGEventSource.buttonState(.combinedSessionState, button: .left),
              let down = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left),
              let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left) else {
            return (false, "现在无法点击 Codex 按钮，请松开鼠标后重试")
        }
        // Codex can acknowledge AXPress without dispatching a click to its composer.
        for event in [down, up] {
            event.setIntegerValueField(.mouseEventClickState, value: 1)
            event.post(tap: .cghidEventTap)
        }
        return (true, message)
    }

    private func visiblePoint(for button: AXUIElement) -> CGPoint? {
        guard let position = value(button, kAXPositionAttribute), CFGetTypeID(position) == AXValueGetTypeID(),
              let size = value(button, kAXSizeAttribute), CFGetTypeID(size) == AXValueGetTypeID() else { return nil }
        var origin = CGPoint.zero
        var dimensions = CGSize.zero
        guard AXValueGetValue(unsafeBitCast(position, to: AXValue.self), .cgPoint, &origin),
              AXValueGetValue(unsafeBitCast(size, to: AXValue.self), .cgSize, &dimensions),
              dimensions.width > 1, dimensions.height > 1 else { return nil }
        let frame = CGRect(origin: origin, size: dimensions)
        guard frame.midX.isFinite, frame.midY.isFinite else { return nil }
        let center = CGPoint(x: frame.midX, y: frame.midY)
        let dx = min(6, frame.width / 4)
        let dy = min(6, frame.height / 4)
        let points = [center, CGPoint(x: center.x + dx, y: center.y), CGPoint(x: center.x - dx, y: center.y),
                      CGPoint(x: center.x, y: center.y + dy), CGPoint(x: center.x, y: center.y - dy)]
        let system = AXUIElementCreateSystemWide()
        return points.first { point in
            var hit: AXUIElement?
            guard AXUIElementCopyElementAtPosition(system, Float(point.x), Float(point.y), &hit) == .success else { return false }
            for _ in 0..<6 {
                guard let node = hit else { return false }
                if CFEqual(node, button) { return true }
                // The hit can be the icon or a group inside the button.
                let role = value(node, kAXRoleAttribute) as? String
                guard role == kAXImageRole || role == kAXGroupRole || role == kAXStaticTextRole else { return false }
                hit = element(node, kAXParentAttribute)
            }
            return false
        }
    }
}
