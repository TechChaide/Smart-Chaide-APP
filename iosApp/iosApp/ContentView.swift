import SwiftUI
import UIKit
import ChaideApp

/// Envuelve la UI Compose Multiplatform (la MISMA que Android, ver
/// app/src/commonMain/.../App.kt) dentro de SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
            .background(Color(red: 0x0B / 255, green: 0x2E / 255, blue: 0x63 / 255).ignoresSafeArea())
    }
}
