# Nintendo Switch Joy-Con Implementation Plan

## Overview

This document outlines the architectural approach for adding Nintendo Switch Joy-Con (right and left) gamepad support to the Nyxx Server. The implementation will extend the existing UDP-based protocol to handle Joy-Con input data and map it to Xbox 360 virtual controllers via ViGEmBus.

## Current Architecture

### Existing Components

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT APP (Android)                      │
│  ┌─────────────────┐  UDP Port 55555  ┌──────────────────────┐  │
│  │  UDP Client     │◄──────────────────►│  Discovery Listener  │  │
│  │  (Input Data)   │                    │  (Port 55555)        │  │
│  └────────┬────────┘                    └──────────┬───────────┘  │
│           │                                       │              │
│           │ UDP Input Data                        │              │
│           ▼                                       │              │
│  ┌──────────────────────────────────────┐           │              │
│  │  UDP Input Handler (MainWindow)      │           │              │
│  │  - Parses button/axis data           │           │              │
│  │  - Maps to GamepadStateArgs          │           │              │
│  │  - Broadcasts to GamepadServer       │───────────┘              │
│  └─────────────────┬────────────────────┘                          │
└────────────────────┼────────────────────────────────────────────────┘
                     │ UDP Input Data
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                      GAMEPAD SERVER                              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  GamepadServer.cs                                          │  │
│  │  - Manages up to 8 players (4 Xbox 360 controllers + 4 DSU-only)          │  │
│  │  - UDP listening on dynamic port                         │  │
│  │  - ViGEmBus integration (Nefarius.ViGEm.Client)          │  │
│  │  - Button/axis mapping to Xbox 360                         │  │
│  └─────────────────┬─────────────────────────────────────────┘  │
│                    │                                              │
│                    │ Cemuhook Protocol                            │
│                    ▼                                              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  CemuhookServer.cs                                         │  │
│  │  - Port 26760 UDP broadcast                              │  │
│  │  - DSU packet format for Cemu compatibility              │  │
│  │  - Controller info, motion data, battery status          │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Joy-Con Button Layout Reference

### Nintendo Switch Pro Controller / Joy-Con Button Mapping

```
Right Joy-Con (Player 1 default):
┌─────────────────────────────────────────────────────────┐
│  [R]  [ZR]  [SYNC]  [SL]  [SR]  [RC]  [RS]  [REMU]      │
│                                                         │
│  [Y]  [X]  [B]  [A]                                    │
│                                                         │
│  [L]  [ZL]  [LD]  [RD]  [LU]  [RU]                    │
│                                                         │
│        [L3]      [R3]      [START]  [SELECT]  [HOME]    │
│                                                         │
│              [LEFT STICK]     [RIGHT STICK]             │
└─────────────────────────────────────────────────────────┘

Left Joy-Con (Player 2+):
┌─────────────────────────────────────────────────────────┐
│  [L]  [ZL]  [SL]  [SR]  [LC]  [LS]  [LMCU]            │
│                                                         │
│  [Y]  [X]  [B]  [A]                                    │
│                                                         │
│  [R]  [ZR]  [LD]  [RD]  [LU]  [RU]                    │
│                                                         │
│  [L3]      [R3]      [START]  [SELECT]  [HOME]        │
│                                                         │
│              [LEFT STICK]     [RIGHT STICK]             │
└─────────────────────────────────────────────────────────┘
```

## Xbox 360 Button Mapping for Joy-Con

| Joy-Con Button | Xbox 360 Equivalent | Bit Flag |
|----------------|---------------------|----------|
| A              | A                   | 0x1000   |
| B              | B                   | 0x2000   |
| X              | X                   | 0x4000   |
| Y              | Y                   | 0x8000   |
| Bumper (L)     | LB                  | 0x0100   |
| Bumper (R)     | RB                  | 0x0200   |
| Trigger (L)    | LT (analog 0-255)   | -        |
| Trigger (R)    | RT (analog 0-255)   | -        |
| D-Pad Up       | D-Pad Up            | 0x0001   |
| D-Pad Down     | D-Pad Down          | 0x0002   |
| D-Pad Left     | D-Pad Left          | 0x0004   |
| D-Pad Right    | D-Pad Right         | 0x0008   |
| Start          | Start               | 0x0010   |
| Select         | Back                | 0x0020   |
| Home           | Guide               | 0x0400   |
| L3 (Stick Click)| Left Thumb         | 0x0040   |
| R3 (Stick Click)| Right Thumb        | 0x0080   |

## Right vs Left Joy-Con Differentiation

### Player Assignment Strategy

```
Player Index | Default Joy-Con | Can Override | Navigation Control
-------------|-----------------|--------------|-------------------
1            | Right (R)       | Yes          | YES (Primary)
2            | Left (L)        | Yes          | NO
3            | Right (R)       | Yes          | NO
4            | Left (L)        | Yes          | NO
```

### Selection Mechanism (Client-Side)

When a player connects, they can select their Joy-Con type:
1. **Player 1**: Auto-assigned Right Joy-Con (navigation control)
2. **Player 2+**: Can choose Left or Right via UI selection in client app

## UDP Protocol Extension for Joy-Con

### Current Protocol (Simplified)

```
[PING] - 4 bytes: "PING"
[PONG:n] - 5 bytes: "PONG:{player_index}"
[DISCONNECT] - 10 bytes: "DISCONNECT"
[Input Data] - Variable: {version, seq, buttons, lx, ly, rx, ry, lt, rt, [motion...]}
```

### Proposed Joy-Con Input Packet Format

```
Byte Offset | Size | Field           | Description
------------|------|-----------------|------------------------------------------
0           | 1    | version         | Protocol version (0x01 for Joy-Con)
1-2         | 2    | sequence        | Packet sequence number
3-4         | 2    | buttons         | Button bitmask (Xbox 360 layout)
5-6         | 2    | lx              | Left stick X (-32768 to 32767)
7-8         | 2    | ly              | Left stick Y (-32768 to 32767)
9-10        | 2    | rx              | Right stick X (-32768 to 32767)
11-12       | 2    | ry              | Right stick Y (-32768 to 32767)
13          | 1    | lt              | Left trigger (0-255)
14          | 1    | rt              | Right trigger (0-255)
15          | 4    | accel_x         | Accelerometer X (m/s², float)
16-19       | 4    | accel_y         | Accelerometer Y (m/s², float)
20-23       | 4    | accel_z         | Accelerometer Z (m/s², float)
24-27       | 4    | gyro_x          | Gyroscope X (rad/s, float)
28-31       | 4    | gyro_y          | Gyroscope Y (rad/s, float)
32-35       | 4    | gyro_z          | Gyroscope Z (rad/s, float)
36          | 1    | joycon_type     | 0=Right, 1=Left, 2=Pro
37          | 1    | battery         | 0=Empty, 1=25%, 2=50%, 3=75%, 4=Full
38-41       | 4    | timestamp_us    | Unix timestamp microseconds
```

## Implementation Steps

### Phase 1: Server-Side Protocol Support

1. **Extend GamepadStateArgs** (`GamepadServer.cs`)
   - Add `JoyConType` enum (Right, Left, Pro)
   - Add `BatteryLevel` property
   - Add `InputSource` property (to track Joy-Con vs other controllers)

2. **Add Joy-Con Input Parser** (`MainWindow.xaml.cs`)
   - Create new UDP packet handler for Joy-Con specific format
   - Parse extended Joy-Con data (motion, battery, type)
   - Map to existing GamepadStateArgs

3. **Implement Player Assignment Logic**
   - Player 1 always gets Right Joy-Con (navigation)
   - Players 2-4 can select Left or Right
   - Track Joy-Con type per player slot

### Phase 2: Client-Side Integration (Android/Kotlin)

1. **Add Joy-Con Connection UI**
   - Player selection screen
   - Joy-Con type selection (Right/Left)
   - Connection status display

2. **Implement Joy-Con Input Handling**
   - Parse Joy-Con HID reports
   - Convert to Xbox 360 button mapping
   - Send via extended UDP protocol

### Phase 3: Enhanced Features

1. **Motion Control Support**
   - Enable gyro/accelerometer data transmission
   - Map to Xbox 360 motion (if supported by ViGEm target)

2. **Battery Status Display**
   - Send battery level to client
   - Display in UI

3. **Connection Management**
   - Handle Joy-Con disconnections
   - Auto-reconnect logic
   - Slot reassignment

## Code Changes Required

### File: `GamepadStateArgs.cs` (or in `GamepadServer.cs`)

```csharp
public enum JoyConType
{
    Right = 0,
    Left = 1,
    Pro = 2
}

public class GamepadStateArgs : EventArgs
{
    public int PlayerIndex { get; set; }
    public short LX { get; set; }
    public short LY { get; set; }
    public short RX { get; set; }
    public short RY { get; set; }
    public byte LT { get; set; }
    public byte RT { get; set; }
    public ushort Buttons { get; set; }
    public float AccelX { get; set; }
    public float AccelY { get; set; }
    public float AccelZ { get; set; }
    public float GyroX { get; set; }
    public float GyroY { get; set; }
    public float GyroZ { get; set; }
    
    // NEW: Joy-Con specific properties
    public JoyConType JoyConType { get; set; } = JoyConType.Right;
    public byte BatteryLevel { get; set; } = 4; // 0-4 scale
    public bool HasMotionSupport { get; set; } = true;
}
```

### File: `MainWindow.xaml.cs` - New Input Handler

```csharp
// Add to MainWindow.xaml.cs
private void HandleJoyConInput(IPEndPoint remoteEP, byte[] data)
{
    // Parse Joy-Con specific packet format
    // Map to GamepadStateArgs with Joy-Con specific properties
    // Broadcast to GamepadServer
}
```

### File: `MainWindow.xaml.cs` - UDP Server Loop Update

```csharp
// Update ServerLoop to handle Joy-Con packet format
// Check for Joy-Con packet signature (version byte = 0x01)
// Parse extended data if present
```

## Configuration Options

### Joy-Con Assignment Modes

1. **Auto Mode**: 
   - Player 1 = Right Joy-Con
   - Player 2 = Left Joy-Con
   - etc.

2. **Manual Mode**:
   - Player selects their Joy-Con type on connection
   - Stored in configuration

3. **Swap Mode**:
   - Right Joy-Con can be assigned to any player
   - Left Joy-Con can be assigned to any player
   - Navigation control stays with Player 1

## Testing Strategy

1. **Unit Tests**
   - Button mapping verification
   - Packet parsing validation
   - Player assignment logic

2. **Integration Tests**
   - Full UDP round-trip
   - ViGEmBus controller simulation
   - Multi-player scenarios

3. **Manual Testing**
   - Joy-Con connection/disconnection
   - Button press verification
   - Motion data accuracy

## Future Enhancements

1. **HD Rumble Support**
   - Send rumble commands to Joy-Con via HID
   - Map from Xbox 360 rumble feedback

2. **NFC Support** (for amiibo)
   - Extension for Pro Controller

3. **System Voice Support**
   - Use Joy-Con IR camera (Right only)

4. **Multiple Joy-Con Per Player**
   - Combined L+R for single player
   - Horizontal mode detection

## References

- [Nintendo Switch Joy-Con Developer Guide](https://switchdevwiki.com/)
- [Joy-Con HID Report Descriptor](https://raw.githubusercontent.com/dekuNukem/Nintendo_Switch_Reverse_Engineering/master/Joy-Con_HID_Report_Descriptor.png)
- [CemuHook Protocol Documentation](https://github.com/CemuProject/Cemu/blob/master/src/kernel/hid.cpp)
- [ViGEmBus Documentation](https://github.com/ViGEm/ViGEmBus)

---

**Document Version**: 1.0  
**Last Updated**: 2026-07-09  
**Author**: Claude Code (AI Assistant)