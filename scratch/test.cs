using System;
using Nefarius.ViGEm.Client.Targets.Xbox360;

class Program
{
    static void Main()
    {
        Console.WriteLine($"Up: {(ushort)Xbox360Button.Up}");
        Console.WriteLine($"Down: {(ushort)Xbox360Button.Down}");
        Console.WriteLine($"Left: {(ushort)Xbox360Button.Left}");
        Console.WriteLine($"Right: {(ushort)Xbox360Button.Right}");
        Console.WriteLine($"Start: {(ushort)Xbox360Button.Start}");
        Console.WriteLine($"Back: {(ushort)Xbox360Button.Back}");
        Console.WriteLine($"LeftThumb: {(ushort)Xbox360Button.LeftThumb}");
        Console.WriteLine($"RightThumb: {(ushort)Xbox360Button.RightThumb}");
        Console.WriteLine($"LeftShoulder: {(ushort)Xbox360Button.LeftShoulder}");
        Console.WriteLine($"RightShoulder: {(ushort)Xbox360Button.RightShoulder}");
        Console.WriteLine($"Guide: {(ushort)Xbox360Button.Guide}");
        Console.WriteLine($"A: {(ushort)Xbox360Button.A}");
        Console.WriteLine($"B: {(ushort)Xbox360Button.B}");
        Console.WriteLine($"X: {(ushort)Xbox360Button.X}");
        Console.WriteLine($"Y: {(ushort)Xbox360Button.Y}");
    }
}
