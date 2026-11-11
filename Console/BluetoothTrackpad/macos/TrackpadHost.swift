import AppKit
import ApplicationServices
import Carbon.HIToolbox
import CoreBluetooth
import CryptoKit
import OSLog

private let serviceID = CBUUID(string: "E2A91F01-AB0D-4A69-A4B3-CA4FA1B0E492")
private let challengeID = CBUUID(string: "E2A91F02-AB0D-4A69-A4B3-CA4FA1B0E492")
private let commandID = CBUUID(string: "E2A91F03-AB0D-4A69-A4B3-CA4FA1B0E492")
private let consoleReplyID = CBUUID(string: "E2A91F04-AB0D-4A69-A4B3-CA4FA1B0E492")

struct TrackpadPacket {
    enum Kind: UInt8 {
        case authenticate = 0, move, scroll, leftClick, rightClick, heartbeat, drag, endDrag, switchSpace
        case listApps = 16, openApp, quitApp, codexDictation, codexSend, codexStop, codexNew, appIcon, iconChunk, appState, catalogStatus, catalogState

        var isConsole: Bool { rawValue >= Self.listApps.rawValue }
    }

    let kind: Kind
    let sequence: UInt32
    let x: Int
    let y: Int

    init?(data: Data, nonce: Data, key: SymmetricKey) {
        let bytes = [UInt8](data)
        guard bytes.count == 17, let kind = Kind(rawValue: bytes[0]) else { return nil }

        let header = bytes.prefix(9)
        let expected = HMAC<SHA256>.authenticationCode(for: nonce + Data(header), using: key)
        let difference = zip(bytes.suffix(8), expected.prefix(8)).reduce(UInt8(0)) { result, pair in
            result | (pair.0 ^ pair.1)
        }
        guard difference == 0 else { return nil }

        self.kind = kind
        sequence = header[1...4].reduce(0) { ($0 << 8) | UInt32($1) }
        x = Int(Int16(bitPattern: UInt16(bytes[5]) << 8 | UInt16(bytes[6])))
        y = Int(Int16(bitPattern: UInt16(bytes[7]) << 8 | UInt16(bytes[8])))
    }
}

private final class TrackpadHost: NSObject, NSApplicationDelegate, CBPeripheralManagerDelegate {
    private static let dragLease: TimeInterval = 1

    private struct Session {
        let central: UUID
        let nonce: Data
        var sequence: UInt32 = 0
        var lastActivity = ProcessInfo.processInfo.systemUptime
    }

    private struct Drag {
        var location: CGPoint
        var deadline: TimeInterval
    }

    private let passcode: String = {
        let defaults = UserDefaults.standard
        let storageKey = "TrackpadPairingCode"
        if let saved = defaults.string(forKey: storageKey),
           saved.range(of: "^[0-9]{6}$", options: .regularExpression) != nil {
            defaults.set(saved, forKey: storageKey)
            return saved
        }
        let code = String(format: "%06d", Int.random(in: 0...999_999))
        defaults.set(code, forKey: storageKey)
        return code
    }()
    private lazy var key = SymmetricKey(data: Data(passcode.utf8))
    private var challenges: [UUID: Data] = [:]
    private var session: Session?
    private let console = ConsoleActions()
    private var consoleReply: Data?
    private var manager: CBPeripheralManager!
    private var window: NSWindow!
    private var statusItem: NSStatusItem!
    private let menuConnection = NSMenuItem(title: "正在启动蓝牙…", action: nil, keyEquivalent: "")
    private let menuPermission = NSMenuItem(title: "正在检查控制权限…", action: nil, keyEquivalent: "")
    private var timer: Timer?
    private var dragTimer: Timer?
    private var drag: Drag?
    private let status = NSTextField(wrappingLabelWithString: "正在启动蓝牙…")
    private let permission = NSTextField(wrappingLabelWithString: "")
    private let counts = NSTextField(labelWithString: "")
    private let logger = Logger(subsystem: "local.trackpad.console.host", category: "trackpad")
    private var advertisingStatus = "正在启动蓝牙…"
    private var eventPostingAllowed = false
    private var connections = 0
    private var movements = 0
    private var clicks = 0
    private var scrolls = 0
    private var drags = 0

    func applicationDidFinishLaunching(_ notification: Notification) {
        let title = NSTextField(labelWithString: "Trackpad 控制台 · Mac 接收端")
        title.font = .boldSystemFont(ofSize: 21)
        let instructions = NSTextField(wrappingLabelWithString: "请在手机控制台测试版输入下方 6 位码。关闭窗口后仍可使用。")
        let code = NSTextField(labelWithString: passcode)
        code.font = .monospacedSystemFont(ofSize: 27, weight: .medium)
        code.isSelectable = true

        let grant = NSButton(title: "授权控制鼠标", target: self, action: #selector(requestPermission))
        let stack = NSStackView(views: [title, instructions, code, status, permission, counts, grant])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 13

        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 290),
                          styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "Trackpad 控制台测试"
        window.isReleasedWhenClosed = false
        let content = window.contentView!
        content.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: content.topAnchor, constant: 23)
        ])
        window.center()
        setupStatusMenu()
        refreshStatus()
        if !eventPostingAllowed && !CommandLine.arguments.contains("--background") { showWindow(nil) }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.refreshStatus() }
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showWindow(nil)
        return false
    }

    private func setupStatusMenu() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let image = NSImage(systemSymbolName: "rectangle.and.hand.point.up.left", accessibilityDescription: "Trackpad 控制台") {
            image.isTemplate = true
            statusItem.button?.image = image
        } else {
            statusItem.button?.title = "TP"
        }
        statusItem.button?.toolTip = "Trackpad 控制台接收端"
        let menu = NSMenu()
        menu.autoenablesItems = false
        menuConnection.isEnabled = false
        menuPermission.isEnabled = false
        menu.addItem(menuConnection)
        menu.addItem(menuPermission)
        menu.addItem(.separator())
        let show = NSMenuItem(title: "显示连接窗口", action: #selector(showWindow(_:)), keyEquivalent: "")
        show.target = self
        menu.addItem(show)
        let permissions = NSMenuItem(title: "检查控制权限", action: #selector(requestPermission), keyEquivalent: "")
        permissions.target = self
        menu.addItem(permissions)
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "退出接收端", action: #selector(quitReceiver), keyEquivalent: "")
        quit.target = self
        menu.addItem(quit)
        statusItem.menu = menu
    }

    @objc private func showWindow(_ sender: Any?) {
        window.makeKeyAndOrderFront(nil)
        if #available(macOS 14, *) { NSApp.activate() }
        else { NSApp.activate(ignoringOtherApps: true) }
    }

    @objc private func quitReceiver() { NSApp.terminate(nil) }

    func applicationWillTerminate(_ notification: Notification) {
        endDrag()
    }

    @objc private func requestPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        refreshStatus()
    }

    private func refreshStatus() {
        if let session, ProcessInfo.processInfo.systemUptime - session.lastActivity > 7 {
            endDrag()
            self.session = nil
            resetConsole()
            logger.info("Link expired")
        }
        status.stringValue = session == nil ? advertisingStatus : "手机已连接"
        eventPostingAllowed = AXIsProcessTrusted() || CGPreflightPostEventAccess()
        permission.stringValue = eventPostingAllowed ? "鼠标控制已授权" : "请在「隐私与安全性 → 辅助功能」授权。若提示退出，请重新打开此接收端。"
        menuConnection.title = status.stringValue
        menuPermission.title = eventPostingAllowed ? "控制权限已授权" : "需要授权辅助功能"
        statusItem.button?.toolTip = "Trackpad 控制台接收端 · " + status.stringValue
        counts.stringValue = "连接 \(connections) 次 · 移动 \(movements) · 点击 \(clicks) · 滚动 \(scrolls) · 拖移 \(drags)"
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        endDrag()
        session = nil
        resetConsole()
        challenges.removeAll()
        switch peripheral.state {
        case .poweredOn:
            advertisingStatus = "正在发布蓝牙服务…"
            peripheral.removeAllServices()
            let challenge = CBMutableCharacteristic(type: challengeID, properties: [.read],
                                                    value: nil, permissions: [.readable])
            let command = CBMutableCharacteristic(type: commandID, properties: [.write, .writeWithoutResponse],
                                                  value: nil, permissions: [.writeable])
            let consoleReply = CBMutableCharacteristic(type: consoleReplyID, properties: [.read],
                                                       value: nil, permissions: [.readable])
            let service = CBMutableService(type: serviceID, primary: true)
            service.characteristics = [challenge, command, consoleReply]
            peripheral.add(service)
        case .poweredOff:
            advertisingStatus = "请打开 Mac 蓝牙"
        case .unauthorized:
            advertisingStatus = "请在「隐私与安全性 → 蓝牙」授权此接收端"
        case .unsupported:
            advertisingStatus = "此 Mac 无法发布 BLE 服务"
        default:
            advertisingStatus = "正在等待蓝牙…"
        }
        refreshStatus()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            advertisingStatus = "发布蓝牙服务失败（\(error.localizedDescription)）"
            refreshStatus()
            return
        }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceID]])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        advertisingStatus = error.map { "蓝牙广播失败（\($0.localizedDescription)）" } ?? "等待手机连接或重连"
        if error == nil { logger.info("BLE service is advertising") }
        refreshStatus()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == consoleReplyID {
            guard session?.central == request.central.identifier, let data = consoleReply else {
                peripheral.respond(to: request, withResult: .insufficientAuthorization)
                return
            }
            guard request.offset >= 0, request.offset <= data.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = data.subdata(in: request.offset..<data.count)
            peripheral.respond(to: request, withResult: .success)
            return
        }
        guard request.characteristic.uuid == challengeID, request.offset == 0 else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        if challenges.count >= 16 { challenges.removeAll() }
        var random = SystemRandomNumberGenerator()
        let nonce = Data((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &random) })
        challenges[request.central.identifier] = nonce
        request.value = nonce
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        guard first.characteristic.uuid == commandID, first.offset == 0,
              let data = first.value else {
            peripheral.respond(to: first, withResult: .invalidAttributeValueLength)
            return
        }

        let central = first.central.identifier
        if requests.count == 1, data.first == TrackpadPacket.Kind.authenticate.rawValue,
           let nonce = challenges.removeValue(forKey: central),
           let packet = TrackpadPacket(data: data, nonce: nonce, key: key), packet.sequence == 0 {
            endDrag()
            session = Session(central: central, nonce: nonce)
            resetConsole()
            connections += 1
            logger.info("Phone authenticated, session \(self.connections)")
            peripheral.respond(to: first, withResult: .success)
            refreshStatus()
            return
        }

        guard var current = session, current.central == central else {
            peripheral.respond(to: first, withResult: .insufficientAuthorization)
            return
        }
        var packets: [TrackpadPacket] = []
        for request in requests {
            guard request.central.identifier == central, request.characteristic.uuid == commandID,
                  request.offset == 0, let bytes = request.value,
                  let packet = TrackpadPacket(data: bytes, nonce: current.nonce, key: key),
                  packet.kind != .authenticate, packet.sequence > current.sequence else {
                peripheral.respond(to: first, withResult: .insufficientAuthorization)
                return
            }
            current.sequence = packet.sequence
            packets.append(packet)
        }
        if let packet = packets.first(where: { $0.kind.isConsole }) {
            guard packets.count == 1 else {
                peripheral.respond(to: first, withResult: .invalidPdu)
                return
            }
            endDrag()
            consoleReply = console.respond(to: packet)
            current.lastActivity = ProcessInfo.processInfo.systemUptime
            session = current
            peripheral.respond(to: first, withResult: .success)
            return
        }
        current.lastActivity = ProcessInfo.processInfo.systemUptime
        session = current
        peripheral.respond(to: first, withResult: .success)
        // WindowServer checks the posting permission. Its preflight result can lag a grant.
        for packet in packets { perform(packet) }
    }

    private func resetConsole() {
        consoleReply = nil
        console.reset()
    }

    private func display(at point: CGPoint) -> CGDirectDisplayID? {
        var display = CGDirectDisplayID()
        var count: UInt32 = 0
        guard CGGetDisplaysWithPoint(point, 1, &display, &count) == .success, count > 0 else { return nil }
        return display
    }

    private func target(from point: CGPoint, by packet: TrackpadPacket) -> CGPoint {
        var next = CGPoint(x: point.x + CGFloat(packet.x), y: point.y + CGFloat(packet.y))
        if display(at: next) == nil, let current = display(at: point) {
            let bounds = CGDisplayBounds(current)
            next.x = min(max(next.x, bounds.minX), bounds.maxX - 1)
            next.y = min(max(next.y, bounds.minY), bounds.maxY - 1)
        }
        return next
    }

    private func postDrag(_ packet: TrackpadPacket) {
        guard eventPostingAllowed, packet.x != 0 || packet.y != 0,
              let point = drag?.location ?? CGEvent(source: nil)?.location else { return }
        let next = target(from: point, by: packet)
        guard let movement = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged,
                                     mouseCursorPosition: next, mouseButton: .left) else { return }
        if drag == nil {
            guard let down = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown,
                                     mouseCursorPosition: point, mouseButton: .left) else { return }
            down.setIntegerValueField(.mouseEventClickState, value: 1)
            down.post(tap: .cghidEventTap)
            drags += 1
            logger.info("Started three-finger drag")
            let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self] _ in self?.checkDragLease() }
            RunLoop.main.add(timer, forMode: .common)
            dragTimer = timer
        }
        drag = Drag(location: next, deadline: ProcessInfo.processInfo.systemUptime + Self.dragLease)
        movement.setIntegerValueField(.mouseEventDeltaX, value: Int64((next.x - point.x).rounded()))
        movement.setIntegerValueField(.mouseEventDeltaY, value: Int64((next.y - point.y).rounded()))
        movement.post(tap: .cghidEventTap)
    }

    private func checkDragLease() {
        guard let drag, ProcessInfo.processInfo.systemUptime >= drag.deadline else { return }
        endDrag()
    }

    private func endDrag() {
        guard let drag else { return }
        self.drag = nil
        dragTimer?.invalidate()
        dragTimer = nil
        let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp,
                         mouseCursorPosition: drag.location, mouseButton: .left)
        up?.setIntegerValueField(.mouseEventClickState, value: 1)
        up?.post(tap: .cghidEventTap)
        logger.info("Released three-finger drag")
    }

    private func switchSpace(direction: Int) {
        guard direction == -1 || direction == 1,
              let source = CGEventSource(stateID: .hidSystemState) else { return }
        let key = CGKeyCode(direction < 0 ? kVK_LeftArrow : kVK_RightArrow)
        let control = CGKeyCode(kVK_Control)
        let arrowFlags: CGEventFlags = [.maskControl, .maskSecondaryFn, .maskNumericPad]
        var edges: [(key: CGKeyCode, down: Bool, flags: CGEventFlags)] = [
            (key, true, arrowFlags), (key, false, arrowFlags)
        ]
        // Mission Control expects the modifier edges and the flags of a physical arrow key.
        if !CGEventSource.flagsState(.combinedSessionState).contains(.maskControl) {
            edges.insert((control, true, .maskControl), at: 0)
            edges.append((control, false, []))
        }
        let events = edges.compactMap { edge -> CGEvent? in
            guard let event = CGEvent(keyboardEventSource: source, virtualKey: edge.key, keyDown: edge.down) else { return nil }
            event.flags = edge.flags
            return event
        }
        guard events.count == edges.count else { return }
        for event in events { event.post(tap: .cghidEventTap) }
        logger.info("Posted four-finger space switch")
    }

    private func perform(_ packet: TrackpadPacket) {
        switch packet.kind {
        case .drag:
            postDrag(packet)
            return
        case .heartbeat where packet.x == 1:
            drag?.deadline = ProcessInfo.processInfo.systemUptime + Self.dragLease
            return
        default:
            endDrag()
        }

        switch packet.kind {
        case .move:
            guard let point = CGEvent(source: nil)?.location else { return }
            let next = target(from: point, by: packet)
            // Update the hardware cursor immediately; the event also delivers hover to applications.
            if eventPostingAllowed { _ = CGWarpMouseCursorPosition(next) }
            CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: next,
                    mouseButton: .left)?.post(tap: .cghidEventTap)
            movements += 1
            if movements == 1 { logger.info("Posted first move") }
        case .scroll:
            CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                    wheel1: Int32(packet.y), wheel2: Int32(packet.x), wheel3: 0)?.post(tap: .cghidEventTap)
            scrolls += 1
            if scrolls == 1 { logger.info("Posted first scroll") }
        case .leftClick, .rightClick:
            guard let point = CGEvent(source: nil)?.location else { return }
            let right = packet.kind == .rightClick
            let button: CGMouseButton = right ? .right : .left
            let types: [CGEventType] = right ? [.rightMouseDown, .rightMouseUp] : [.leftMouseDown, .leftMouseUp]
            for type in types {
                CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point,
                        mouseButton: button)?.post(tap: .cghidEventTap)
            }
            clicks += 1
            if clicks == 1 { logger.info("Posted first click") }
        case .switchSpace:
            switchSpace(direction: packet.x)
        case .authenticate, .heartbeat, .drag, .endDrag,
             .listApps, .openApp, .quitApp, .codexDictation, .codexSend, .codexStop, .codexNew, .appIcon, .iconChunk, .appState, .catalogStatus, .catalogState:
            break
        }
    }
}

@main
enum TrackpadApp {
    static func main() {
        let app = NSApplication.shared
        let delegate = TrackpadHost()
        app.setActivationPolicy(.accessory)
        app.delegate = delegate
        app.run()
        withExtendedLifetime(delegate) {}
    }
}
