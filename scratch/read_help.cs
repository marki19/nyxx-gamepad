using System;
using System.Diagnostics;
using System.Threading;
using System.Windows.Automation;
using System.Runtime.InteropServices;

class Program
{
    static void Main()
    {
        Process p = Process.Start("c:\\Programming Projects\\personal projects\\NativeGamepad\\installer\\ViGEmBus_Setup.exe", "/?");
        Thread.Sleep(2000);
        
        // Find the window
        var window = AutomationElement.RootElement.FindFirst(TreeScope.Children, new PropertyCondition(AutomationElement.ProcessIdProperty, p.Id));
        if (window != null)
        {
            Console.WriteLine("Window found: " + window.Current.Name);
            var texts = window.FindAll(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Text));
            foreach (AutomationElement text in texts)
            {
                Console.WriteLine("Text: " + text.Current.Name);
            }
            p.Kill();
        }
        else
        {
            Console.WriteLine("No window found.");
        }
    }
}
