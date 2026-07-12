using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;

namespace NativeGamepadServer {
    class Ds4Test {
        public static void Test() {
            var client = new ViGEmClient();
            var ds4 = client.CreateDualShock4Controller();
            ds4.SetAxisValue(DualShock4Axis.LeftThumbX, 128);
            ds4.SetSliderValue(DualShock4Slider.LeftTrigger, 0);
            ds4.SetButtonState(DualShock4Button.Square, true);
            ds4.SetButtonState(DualShock4SpecialButton.Ps, true);
            
            // Try to set gyro/accel if available?
            // ds4.Report.GyroX = 0;
            // ds4.Report.AccelX = 0;
        }
    }
}
