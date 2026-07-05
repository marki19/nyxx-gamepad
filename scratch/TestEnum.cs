using System;
using System.Reflection;

class Program {
    static void Main() {
        Assembly asm = Assembly.LoadFrom(@"C:\Programming Projects\personal projects\NativeGamepad\server\bin\Release\net8.0-windows\Nefarius.ViGEm.Client.dll");
        foreach (var t in asm.GetTypes()) {
            if (t.Name.Contains("Xbox360Button")) {
                Console.WriteLine(t.FullName);
                foreach (var name in Enum.GetNames(t)) {
                    Console.WriteLine("  " + name + " = " + (ushort)Enum.Parse(t, name));
                }
            }
        }
    }
}
