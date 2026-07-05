using System;
using System.Net;

class Program {
    static void Main() {
        var ip1 = IPAddress.Parse("192.168.1.4");
        var ip2 = IPAddress.Parse("::ffff:192.168.1.4");
        Console.WriteLine(ip1.Equals(ip2));
        Console.WriteLine(ip1.ToString() == ip2.ToString());
        Console.WriteLine(ip1.MapToIPv4().Equals(ip2.MapToIPv4()));
    }
}
