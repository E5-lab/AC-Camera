/// Desktop entry: purely a shell around the web frontend for now — all business logic lives in
/// TypeScript (webapp/src). Commands (file dialogs, printing, …) get added here as milestones need.
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
