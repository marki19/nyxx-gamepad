using System;
using Nefarius.ViGEm.Client.Targets.Xbox360;
class Program
{
    static void Main()
    {
        Console.WriteLine($"Up: {((ushort)Xbox360Button.Up).ToString("X4")}");
        Console.WriteLine($"Down: {((ushort)Xbox360Button.Down).ToString("X4")}");
        Console.WriteLine($"Left: {((ushort)Xbox360Button.Left).ToString("X4")}");
        Console.WriteLine($"Right: {((ushort)Xbox360Button.Right).ToString("X4")}");
        Console.WriteLine($"Start: {((ushort)Xbox360Button.Start).ToString("X4")}");
        Console.WriteLine($"Back: {((ushort)Xbox360Button.Back).ToString("X4")}");
        Console.WriteLine($"LeftThumb: {((ushort)Xbox360Button.LeftThumb).ToString("X4")}");
        Console.WriteLine($"RightThumb: {((ushort)Xbox360Button.RightThumb).ToString("X4")}");
        Console.WriteLine($"LeftShoulder: {((ushort)Xbox360Button.LeftShoulder).ToString("X4")}");
        Console.WriteLine($"RightShoulder: {((ushort)Xbox360Button.RightShoulder).ToString("X4")}");
        Console.WriteLine($"Guide: {((ushort)Xbox360Button.Guide).ToString("X4")}");
        Console.WriteLine($"A: {((ushort)Xbox360Button.A).ToString("X4")}");
        Console.WriteLine($"B: {((ushort)Xbox360Button.B).ToString("X4")}");
        Console.WriteLine($"X: {((ushort)Xbox360Button.X).ToString("X4")}");
        Console.WriteLine($"Y: {((ushort)Xbox360Button.Y).ToString("X4")}");
    }
}
