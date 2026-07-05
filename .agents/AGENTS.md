    ### Virtual Controller Server Lifecycle
When building or modifying virtual controller server components (like ViGEmClient):
1. **Ghost Controller Prevention:** ALWAYS explicitly call `Disconnect()` and `Dispose()` on virtual controller instances (e.g., `IXbox360Controller`) when a client times out or drops the connection.
2. **Ghost Server Port Prevention:** Implement a global idle timeout mechanism. If zero clients are actively connected for a predetermined duration (e.g., 5 minutes), the server application MUST gracefully exit to release the bound UDP/TCP ports.
3. **Multiple Instance Prevention (Ghost Server):** ALWAYS implement a strict single-instance check (e.g., using a named `Mutex`) on application startup. If a second instance is launched, it MUST gracefully signal the first instance to restore itself (e.g., via Named Pipes IPC) and then immediately exit, rather than silently running in the background and binding duplicate ports.
4. **System Tray Lifecycle:** When an application is configured to minimize to the system tray, ALWAYS provide a right-click Context Menu on the tray icon with explicit "Restore" and "Exit" options to prevent the app from becoming an unclosable zombie process.

### Server Execution Constraints
NEVER start the PC server in the background without user visibility. ALWAYS propose the `dotnet run` (or equivalent server start command) via the `run_command` tool so the user is explicitly prompted to approve and execute it in their own terminal.

### Raw Sensor Input Processing (Gyro/Accelerometer)
When implementing raw sensor inputs for game controllers, NEVER pass raw values directly to the network payload. You MUST implement:
1. **Low-Pass Filter**: To eliminate natural hand-jitter and spam.
2. **Deadzone**: A configurable inner deadzone to prevent drift when resting.
3. **Exponential Curve**: An exponential response curve (e.g., `out * out * signum(out)`) for fine center control while allowing 100% lock at the edges.
4. **Calibration**: Always provide a mechanism to set the current physical orientation as the "zero" point.

### Virtual Controller "Pause" Behavior
When implementing a "Pause Input" feature in a virtual controller, NEVER freeze the UI visuals (joysticks, buttons). Visual feedback (knob sliding, button pressing colors) should continue normally so the user feels the app is responsive; only the outgoing network payload transmission should be zeroed/paused.

### Lightweight Code and Zero Bloatware
When adding features or fixing bugs in this project, NEVER introduce unnecessary dependencies, bloated abstractions, or "bloatware" features. The application must remain highly performant and lightweight. Retain ONLY the specific functions requested by the user. Do not add unsolicited UI themes, heavy libraries, or secondary background services unless explicitly requested. Always look for ways to consolidate code (e.g., multiplexing over existing sockets instead of creating new ones) to keep the footprint small.
