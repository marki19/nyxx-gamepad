package com.nativegamepad

object HidDescriptor {
    val GAMEPAD_DESCRIPTOR = intArrayOf(
        0x05, 0x01,        // Usage Page (Generic Desktop Ctrls)
        0x09, 0x05,        // Usage (Game Pad)
        0xA1, 0x01,        // Collection (Application)
        0x85, 0x01,        // Report ID (1)
        
        // --- 16 Buttons ---
        0x05, 0x09,        // Usage Page (Button)
        0x19, 0x01,        // Usage Minimum (0x01)
        0x29, 0x10,        // Usage Maximum (0x10)
        0x15, 0x00,        // Logical Minimum (0)
        0x25, 0x01,        // Logical Maximum (1)
        0x75, 0x01,        // Report Size (1)
        0x95, 0x10,        // Report Count (16)
        0x81, 0x02,        // Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        
        // --- Hat Switch (D-Pad) ---
        0x05, 0x01,        // Usage Page (Generic Desktop Ctrls)
        0x09, 0x39,        // Usage (Hat switch)
        0x15, 0x01,        // Logical Minimum (1)
        0x25, 0x08,        // Logical Maximum (8)
        0x35, 0x00,        // Physical Minimum (0)
        0x46, 0x3B, 0x01,  // Physical Maximum (315)
        0x65, 0x14,        // Unit (System: English Rotation, Length: Centimeter)
        0x75, 0x04,        // Report Size (4)
        0x95, 0x01,        // Report Count (1)
        0x81, 0x42,        // Input (Data,Var,Abs,No Wrap,Linear,Preferred State,Null State)
        
        // --- Padding (4 bits to align to byte) ---
        0x75, 0x04,        // Report Size (4)
        0x95, 0x01,        // Report Count (1)
        0x81, 0x03,        // Input (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        
        // --- 4 Axes (Left/Right Sticks: X, Y, Z, Rz) ---
        // X, Y for Left Stick
        // Z, Rz for Right Stick
        0x05, 0x01,        // Usage Page (Generic Desktop Ctrls)
        0x09, 0x30,        // Usage (X)
        0x09, 0x31,        // Usage (Y)
        0x09, 0x32,        // Usage (Z)
        0x09, 0x35,        // Usage (Rz)
        0x16, 0x00, 0x80,  // Logical Minimum (-32768)
        0x26, 0xFF, 0x7F,  // Logical Maximum (32767)
        0x75, 0x10,        // Report Size (16)
        0x95, 0x04,        // Report Count (4)
        0x81, 0x02,        // Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        
        // --- Analog Triggers (Brake, Accelerator for LT/RT) ---
        0x05, 0x02,        // Usage Page (Sim Ctrls)
        0x09, 0xC5,        // Usage (Brake)
        0x09, 0xC4,        // Usage (Accelerator)
        0x15, 0x00,        // Logical Minimum (0)
        0x26, 0xFF, 0x00,  // Logical Maximum (255)
        0x75, 0x08,        // Report Size (8)
        0x95, 0x02,        // Report Count (2)
        0x81, 0x02,        // Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        
        0xC0               // End Collection
    ).map { it.toByte() }.toByteArray()

    // Report Byte Size:
    // Buttons: 16 bits = 2 bytes
    // Hat Switch + Padding: 4 + 4 bits = 1 byte
    // Axes: 16 bits * 4 = 8 bytes
    // Triggers: 8 bits * 2 = 2 bytes
    // Total Report Size = 13 bytes
}
