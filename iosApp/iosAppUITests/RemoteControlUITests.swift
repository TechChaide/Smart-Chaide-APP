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
///   type:TEXTO        escribe TEXTO en el campo enfocado
///   clear             borra el campo enfocado
///   swipeUp / swipeDown
///   wait:SEG          espera SEG segundos
///   allow             acepta un dialogo de permiso del sistema si aparece
///   shot:NOMBRE       captura de pantalla (adjunta al resultado)
///   tree              vuelca el arbol de accesibilidad (adjunto de texto)
final class RemoteControlUITests: XCTestCase {

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
            case "type": app.typeText(arg)
            case "clear": app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 60))
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
