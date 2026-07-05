using System;
using Nefarius.ViGEm.Client.Targets.Xbox360;
class Program {
    static void Main() {
        foreach (Xbox360Button b in Enum.GetValues(typeof(Xbox360Button))) {
            Console.WriteLine(b.ToString() + " = " + ((ushort)b).ToString("X4"));
        }
    }
}
