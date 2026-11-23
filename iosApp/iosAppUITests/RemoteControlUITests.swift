import XCTest

/// "Control remoto" de la app en el iPhone para pruebas manejadas desde la Mac.
///
/// Apple no permite tocar/capturar la pantalla de un iPhone fisico desde la
/// linea de comandos; XCUITest si. Este test ejecuta una lista de comandos que
/// llega por la variable de entorno CMDS (xcodebuild pasa al runner las
/// variables con prefijo TEST_RUNNER_, p.ej. TEST_RUNNER_CMDS="...").
///
/// Comandos separados por ';':
///   launch            relanza la app desde cero (si no, se reutiliza la que este abierta)
///   tap:X,Y           toca en coordenadas normalizadas 0..1 (x,y) de la pantalla
///   tapText:TEXTO     toca el primer elemento cuya etiqueta contenga TEXTO
///   type:TEXTO        escribe TEXTO tocando las teclas del teclado (toca antes el campo)
///   keys              vuelca las teclas visibles del teclado (diagnostico)
///   clear             borra el campo enfocado (40 x tecla borrar)
///   swipeUp / swipeDown
///   wait:SEG          espera SEG segundos
///   allow             acepta un dialogo de permiso del sistema si aparece
///   shot:NOMBRE       captura de pantalla (adjunta al resultado)
///   tree              vuelca el arbol de accesibilidad (adjunto de texto)
final class RemoteControlUITests: XCTestCase {

    // ---- Escritura tocando el teclado del sistema ----
    // `typeText` de XCUITest falla con Compose Multiplatform ("Neither element nor
    // any descendant has keyboard focus"): el campo de Compose no se expone como
    // elemento enfocado. El teclado de iOS si es accesible, asi que se escribe
    // tocando sus teclas una por una.

    private func visibleKey(_ app: XCUIApplication, label: String) -> XCUIElement? {
        let p = NSPredicate(format: "label == %@ OR identifier == %@", label, label)
        for q in [app.keyboards.keys.matching(p), app.keyboards.buttons.matching(p)] {
            let e = q.firstMatch
            if e.exists && e.isHittable { return e }
        }
        return nil
    }

    private func modeKey(_ app: XCUIApplication, _ hints: [String]) -> XCUIElement? {
        for h in hints {
            let p = NSPredicate(format: "label ==[c] %@ OR identifier ==[c] %@ OR label CONTAINS[c] %@", h, h, h)
            for q in [app.keyboards.keys.matching(p), app.keyboards.buttons.matching(p)] {
                let e = q.firstMatch
                if e.exists && e.isHittable { return e }
            }
        }
        return nil
    }

    private let numbersHints = ["more, numbers", "más, números", "numbers", "números", "123"]
    private let symbolsHints = ["more, symbols", "más, símbolos", "symbols", "símbolos", "#+="]
    private let lettersHints = ["more, letters", "más, letras", "letters", "letras", "ABC"]
    private let shiftHints = ["shift", "mayúsculas", "mayús"]

    private func tapChar(_ ch: String, in app: XCUIApplication) -> Bool {
        if let k = visibleKey(app, label: ch) { k.tap(); return true }
        // Mayuscula: activar shift (una vez) y buscar de nuevo.
        if ch.uppercased() == ch, ch.lowercased() != ch, let s = modeKey(app, shiftHints) {
            s.tap()
            if let k = visibleKey(app, label: ch) { k.tap(); return true }
        }
        // Numeros y luego simbolos; al terminar volver a letras.
        if let n = modeKey(app, numbersHints) {
            n.tap()
            if let k = visibleKey(app, label: ch) { k.tap(); modeKey(app, lettersHints)?.tap(); return true }
            if let s = modeKey(app, symbolsHints) {
                s.tap()
                if let k = visibleKey(app, label: ch) { k.tap(); modeKey(app, lettersHints)?.tap(); return true }
            }
            modeKey(app, lettersHints)?.tap()
        }
        return false
    }

    func typeViaKeyboard(_ text: String, in app: XCUIApplication) {
        guard app.keyboards.firstMatch.waitForExistence(timeout: 3) else {
            XCTFail("No hay teclado visible: toca primero el campo"); return
        }
        for ch in text {
            if !tapChar(String(ch), in: app) { XCTFail("Tecla no encontrada: \(ch)") }
        }
    }

    func clearViaKeyboard(in app: XCUIApplication) {
        guard app.keyboards.firstMatch.waitForExistence(timeout: 3),
              let del = modeKey(app, ["delete", "borrar", "eliminar"]) else { return }
        for _ in 0..<40 { del.tap() }
    }


    func testRun() throws {
        continueAfterFailure = true
        let env = ProcessInfo.processInfo.environment
        let cmds = (env["CMDS"] ?? "shot:estado").split(separator: ";").map { String($0) }
        let app = XCUIApplication()

        // Dialogos del sistema (Bluetooth / Red local): "Permitir"/"Allow"/"OK".
        addUIInterruptionMonitor(withDescription: "permisos") { alert in
            for label in ["Permitir", "Allow", "OK", "Aceptar"] where alert.buttons[label].exists {
                alert.buttons[label].tap(); return true
            }
            return false
        }

        if cmds.first == "launch" { app.launch() } else { app.activate() }
        sleep(1)

        for (i, raw) in cmds.enumerated() {
            let parts = raw.split(separator: ":", maxSplits: 1).map { String($0) }
            let op = parts[0].trimmingCharacters(in: .whitespaces)
            let arg = parts.count > 1 ? parts[1] : ""
            switch op {
            case "launch": break
            case "tap":
                let xy = arg.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
                if xy.count == 2 {
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                        .withOffset(CGVector(dx: xy[0] * app.frame.width, dy: xy[1] * app.frame.height)).tap()
                }
            case "tapText":
                let q = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", arg)).firstMatch
                if q.waitForExistence(timeout: 5) { q.tap() } else { XCTFail("no encontrado: \(arg)") }
            case "type": typeViaKeyboard(arg, in: app)
            case "clear": clearViaKeyboard(in: app)
            case "keys":
                // Diagnostico: lista las teclas visibles del teclado (identifier | label).
                let names = app.keyboards.keys.allElementsBoundByIndex.map { "\($0.identifier) | \($0.label)" }
                let a = XCTAttachment(string: names.joined(separator: "\n"))
                a.name = String(format: "%02d_keys", i)
                a.lifetime = .keepAlways
                add(a)
            case "swipeUp": app.swipeUp()
            case "swipeDown": app.swipeDown()
            case "wait": Thread.sleep(forTimeInterval: Double(arg) ?? 1)
            case "allow": app.tap()   // dispara el interruption monitor si hay dialogo
            case "shot":
                let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                a.name = String(format: "%02d_%@", i, arg.isEmpty ? "shot" : arg)
                a.lifetime = .keepAlways
                add(a)
            case "tree":
                let a = XCTAttachment(string: app.debugDescription)
                a.name = String(format: "%02d_tree", i)
                a.lifetime = .keepAlways
                add(a)
            default: break
            }
        }
    }
}
