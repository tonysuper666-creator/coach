import SwiftUI

@main
struct CoachApp: App {
    @StateObject private var connection = ConnectionModel()

    var body: some Scene {
        WindowGroup { ContentView().environmentObject(connection) }
    }
}
