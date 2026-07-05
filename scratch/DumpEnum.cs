using System;
using System.Reflection;
class Program {
    static void Main() {
        var asm = Assembly.LoadFile(@"C:\Programming Projects\personal projects\NativeGamepad\server\bin\Release\net8.0-windows\win-x64\Nefarius.ViGEm.Client.dll");
        foreach(var t in asm.GetTypes()) {
            if(t.Name.Contains("Xbox360Button")) {
                Console.WriteLine(t.FullName);
                foreach(var v in Enum.GetValues(t)) {
                    Console.WriteLine(v.ToString() + " = " + ((ushort)Convert.ChangeType(v, typeof(ushort))).ToString("X4"));
                }
            }
        }
    }
}
