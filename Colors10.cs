using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace Colors10App
{
    static class Native
    {
        [DllImport("user32.dll")]
        public static extern IntPtr GetDC(IntPtr hWnd);

        [DllImport("user32.dll")]
        public static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

        [DllImport("gdi32.dll")]
        public static extern bool SetDeviceGammaRamp(IntPtr hDC, ushort[] ramp);

        [DllImport("gdi32.dll")]
        public static extern bool GetDeviceGammaRamp(IntPtr hDC, ushort[] ramp);

        [DllImport("user32.dll")]
        public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll")]
        public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    }


    // AMD ADL2 saturation control. ADL is loaded dynamically so the app still starts
    // when AMD drivers / ADL runtime are not installed.
    sealed class ADL2SaturationController : IDisposable
    {
        const int ADL_OK = 0;
        const int AMD_VENDOR_ID = 0x1002;
        const int ADL_DISPLAY_COLOR_SATURATION = 1 << 2;
        const int DISPLAY_CONNECTED_MAPPED = 0x3;

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr MemoryAlloc(int size);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int MainCreate(MemoryAlloc alloc, int connectedOnly, out IntPtr context);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int MainDestroy(IntPtr context);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int AdapterCount(IntPtr context, out int count);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int AdapterInfoGet(IntPtr context, IntPtr info, int size);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int DisplayInfoGet(IntPtr context, int adapter, out int count, out IntPtr info, int forceDetect);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int ColorCapsGet(IntPtr context, int adapter, int display, out int caps, out int valid);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int ColorGet(IntPtr context, int adapter, int display, int type,
            out int current, out int def, out int min, out int max, out int step);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int ColorSet(IntPtr context, int adapter, int display, int type, int value);

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
        struct AdapterInfo
        {
            public int Size, AdapterIndex;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string UDID;
            public int BusNumber, DriverNumber, FunctionNumber, VendorID;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string AdapterName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string DisplayName;
            public int Present, Exist;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string DriverPath;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string DriverPathExt;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string PNPString;
            public int OSDisplayIndex;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
        struct DisplayID
        {
            public int LogicalIndex, PhysicalIndex, LogicalAdapterIndex, PhysicalAdapterIndex;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
        struct DisplayInfo
        {
            public DisplayID ID;
            public int ControllerIndex;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Name;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Manufacturer;
            public int DisplayType, OutputType, Connector, InfoMask, InfoValue;
        }

        sealed class Target
        {
            public int Adapter, Display, Original, Minimum, Maximum, Step;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr LoadLibrary(string fileName);
        [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
        static extern IntPtr GetProcAddress(IntPtr module, string name);
        [DllImport("kernel32.dll")]
        static extern bool FreeLibrary(IntPtr module);

        IntPtr module, context;
        MemoryAlloc allocator;
        MainDestroy destroy;
        ColorSet setColor;
        readonly List<Target> targets = new List<Target>();
        bool initialized;
        public string Status { get; private set; }

        static IntPtr Alloc(int size) { return Marshal.AllocCoTaskMem(size); }

        T Load<T>(string name) where T : class
        {
            IntPtr p = GetProcAddress(module, name);
            if (p == IntPtr.Zero) return null;
            return Marshal.GetDelegateForFunctionPointer(p, typeof(T)) as T;
        }

        public ADL2SaturationController()
        {
            try
            {
                module = LoadLibrary("atiadlxx.dll");
                if (module == IntPtr.Zero)
                {
                    Status = "AMD ADL2 not found (AMD driver required)";
                    return;
                }

                allocator = Alloc;
                MainCreate create = Load<MainCreate>("ADL2_Main_Control_Create");
                destroy = Load<MainDestroy>("ADL2_Main_Control_Destroy");
                AdapterCount getCount = Load<AdapterCount>("ADL2_Adapter_NumberOfAdapters_Get");
                AdapterInfoGet getAdapters = Load<AdapterInfoGet>("ADL2_Adapter_AdapterInfo_Get");
                DisplayInfoGet getDisplays = Load<DisplayInfoGet>("ADL2_Display_DisplayInfo_Get");
                ColorCapsGet getCaps = Load<ColorCapsGet>("ADL2_Display_ColorCaps_Get");
                ColorGet getColor = Load<ColorGet>("ADL2_Display_Color_Get");
                setColor = Load<ColorSet>("ADL2_Display_Color_Set");

                if (create == null || destroy == null || getCount == null || getAdapters == null ||
                    getDisplays == null || getCaps == null || getColor == null || setColor == null)
                {
                    Status = "AMD ADL2 color functions unavailable";
                    return;
                }

                if (create(allocator, 1, out context) != ADL_OK || context == IntPtr.Zero)
                {
                    Status = "AMD driver rejected ADL2 initialization";
                    return;
                }

                int adapterCount;
                if (getCount(context, out adapterCount) != ADL_OK || adapterCount <= 0)
                {
                    Status = "No AMD adapter detected by ADL2";
                    return;
                }

                int adapterSize = Marshal.SizeOf(typeof(AdapterInfo));
                IntPtr adapterBuffer = Marshal.AllocHGlobal(adapterSize * adapterCount);
                try
                {
                    for (int i = 0; i < adapterCount; i++)
                    {
                        IntPtr item = IntPtr.Add(adapterBuffer, i * adapterSize);
                        for (int b = 0; b < adapterSize; b += 4) Marshal.WriteInt32(item, b, 0);
                        Marshal.WriteInt32(item, adapterSize > 0 ? 0 : 0, adapterSize);
                    }

                    if (getAdapters(context, adapterBuffer, adapterSize * adapterCount) != ADL_OK)
                    {
                        Status = "Could not enumerate AMD adapters";
                        return;
                    }

                    int displaySize = Marshal.SizeOf(typeof(DisplayInfo));
                    for (int i = 0; i < adapterCount; i++)
                    {
                        AdapterInfo adapter = (AdapterInfo)Marshal.PtrToStructure(
                            IntPtr.Add(adapterBuffer, i * adapterSize), typeof(AdapterInfo));
                        if (adapter.VendorID != AMD_VENDOR_ID) continue;

                        int displayCount;
                        IntPtr displayBuffer;
                        if (getDisplays(context, adapter.AdapterIndex, out displayCount, out displayBuffer, 0) != ADL_OK ||
                            displayBuffer == IntPtr.Zero) continue;

                        try
                        {
                            for (int j = 0; j < displayCount; j++)
                            {
                                DisplayInfo display = (DisplayInfo)Marshal.PtrToStructure(
                                    IntPtr.Add(displayBuffer, j * displaySize), typeof(DisplayInfo));
                                if ((display.InfoValue & DISPLAY_CONNECTED_MAPPED) != DISPLAY_CONNECTED_MAPPED)
                                    continue;
                                if (display.ID.LogicalAdapterIndex != adapter.AdapterIndex) continue;

                                int caps, valid;
                                if (getCaps(context, adapter.AdapterIndex, display.ID.LogicalIndex, out caps, out valid) != ADL_OK ||
                                    ((caps & valid & ADL_DISPLAY_COLOR_SATURATION) == 0)) continue;

                                int current, def, min, max, step;
                                if (getColor(context, adapter.AdapterIndex, display.ID.LogicalIndex,
                                    ADL_DISPLAY_COLOR_SATURATION, out current, out def, out min, out max, out step) != ADL_OK)
                                    continue;

                                Target t = new Target();
                                t.Adapter = adapter.AdapterIndex;
                                t.Display = display.ID.LogicalIndex;
                                t.Original = current;
                                t.Minimum = min;
                                t.Maximum = max;
                                t.Step = step > 0 ? step : 1;
                                targets.Add(t);
                            }
                        }
                        finally { Marshal.FreeCoTaskMem(displayBuffer); }
                    }
                }
                finally { Marshal.FreeHGlobal(adapterBuffer); }

                if (targets.Count == 0)
                {
                    Status = "AMD driver exposes no controllable saturation display";
                    return;
                }

                initialized = true;
                Status = "AMD ADL2 saturation ready";
            }
            catch (Exception ex)
            {
                Status = "ADL2 initialization failed: " + ex.Message;
            }
        }

        public bool SetSaturation(int value)
        {
            if (!initialized || setColor == null) return false;
            bool allOk = true;
            foreach (Target t in targets)
            {
                double fraction = value / 100.0;
                double requested = fraction < 0
                    ? t.Original + (t.Minimum - t.Original) * (-fraction)
                    : t.Original + (t.Maximum - t.Original) * fraction;
                int adjusted = (int)Math.Round(requested);
                adjusted = Math.Max(t.Minimum, Math.Min(t.Maximum, adjusted));
                adjusted = t.Minimum + (int)Math.Round((adjusted - t.Minimum) / (double)t.Step) * t.Step;
                adjusted = Math.Max(t.Minimum, Math.Min(t.Maximum, adjusted));
                if (setColor(context, t.Adapter, t.Display, ADL_DISPLAY_COLOR_SATURATION, adjusted) != ADL_OK)
                    allOk = false;
            }
            if (!allOk) Status = "AMD driver rejected the saturation change";
            else Status = "AMD ADL2 saturation active";
            return allOk;
        }

        public void Restore()
        {
            if (!initialized || setColor == null) return;
            foreach (Target t in targets)
                setColor(context, t.Adapter, t.Display, ADL_DISPLAY_COLOR_SATURATION, t.Original);
        }

        public void Dispose()
        {
            try { Restore(); } catch { }
            if (context != IntPtr.Zero && destroy != null)
            {
                try { destroy(context); } catch { }
                context = IntPtr.Zero;
            }
            initialized = false;
            if (module != IntPtr.Zero)
            {
                FreeLibrary(module);
                module = IntPtr.Zero;
            }
        }
    }

    class Profile
    {
        public string Name = "";
        public uint Mods;
        public int Vk;
        public int[] Gam = new int[3];
        public int[] Bri = new int[3];
        public int[] Con = new int[3];
        public int Col;
    }

    class BufferedPanel : Panel
    {
        public BufferedPanel()
        {
            this.DoubleBuffered = true;
            this.ResizeRedraw = true;
        }
    }

    static class Program
    {
        [STAThread]
        static void Main()
        {
            bool created;
            using (Mutex mtx = new Mutex(true, "Colors10_SingleInstance_Mutex", out created))
            {
                if (!created) return;
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
        }
    }

    class MainForm : Form
    {
        const int WM_HOTKEY = 0x0312;
        const uint MOD_NOREPEAT = 0x4000;

        static readonly string[] SliderNames = { "Gamma:", "Brightness:", "Contrast:", "Color:" };
        static readonly int[] SMin = { 30, -100, 0, -100 };
        static readonly int[] SMax = { 300, 100, 200, 100 };
        static readonly int[] SDef = { 100, 0, 100, 0 };
        static readonly int[] STick = { 30, 20, 20, 20 };

        // values per channel: 0 = red, 1 = green, 2 = blue
        int[] gam = new int[3];
        int[] bri = new int[3];
        int[] con = new int[3];
        int colorVal;

        int mode; // 0 = linked, 1 = red, 2 = green, 3 = blue
        bool loading;
        bool exiting;

        ushort[] ramp = new ushort[768];
        ushort[] origRamp = new ushort[768];
        bool haveOrig;
        ADL2SaturationController saturationController;

        List<Profile> profiles = new List<Profile>();
        int regCount;
        uint hkMods;
        int hkVk;

        TrackBar[] tb = new TrackBar[4];
        Label[] valLbl = new Label[4];
        BufferedPanel graph;
        RadioButton[] modeBtn = new RadioButton[4];
        ComboBox combo;
        TextBox txtHk;
        Label lblStatus;
        NotifyIcon notify;
        Icon appIcon;

        public MainForm()
        {
            ResetValues();

            Text = "Colors10";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = true;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Font = new Font("Tahoma", 8.25F);
            ClientSize = new Size(500, 442);

            appIcon = MakeAppIcon();
            Icon = appIcon;

            BuildUi();
            BuildTray();

            LoadProfiles();
            ReloadCombo(null);

            try
            {
                IntPtr dc = Native.GetDC(IntPtr.Zero);
                haveOrig = Native.GetDeviceGammaRamp(dc, origRamp);
                Native.ReleaseDC(IntPtr.Zero, dc);
            }
            catch { haveOrig = false; }

            saturationController = new ADL2SaturationController();
            RebuildRamp();
            LoadSliders();

            SystemEvents.PowerModeChanged += OnPowerMode;
            SystemEvents.DisplaySettingsChanged += OnDisplayChanged;
        }

        // ---------------------------------------------------------------- UI

        void BuildUi()
        {
            Font bold = new Font(Font, FontStyle.Bold);

            GroupBox gb = new GroupBox();
            gb.Text = "Color adjustment:";
            gb.SetBounds(12, 8, 476, 304);
            Controls.Add(gb);

            for (int i = 0; i < 4; i++)
            {
                int y = 22 + i * 58;

                Label nameLbl = new Label();
                nameLbl.Text = SliderNames[i];
                nameLbl.Font = bold;
                nameLbl.AutoSize = true;
                nameLbl.Location = new Point(10, y);
                gb.Controls.Add(nameLbl);

                valLbl[i] = new Label();
                valLbl[i].SetBounds(190, y, 72, 14);
                valLbl[i].TextAlign = ContentAlignment.TopRight;
                gb.Controls.Add(valLbl[i]);

                tb[i] = new TrackBar();
                tb[i].Minimum = SMin[i];
                tb[i].Maximum = SMax[i];
                tb[i].Value = SDef[i];
                tb[i].TickFrequency = STick[i];
                tb[i].TickStyle = TickStyle.BottomRight;
                tb[i].SmallChange = 1;
                tb[i].LargeChange = 10;
                tb[i].Tag = i;
                tb[i].SetBounds(4, y + 14, 262, 40);
                tb[i].ValueChanged += OnSlide;
                gb.Controls.Add(tb[i]);
            }

            graph = new BufferedPanel();
            graph.BackColor = Color.Black;
            graph.BorderStyle = BorderStyle.Fixed3D;
            graph.SetBounds(282, 22, 176, 176);
            graph.Paint += PaintGraph;
            gb.Controls.Add(graph);

            Button btnReset = new Button();
            btnReset.Text = "Reset";
            btnReset.SetBounds(10, 266, 70, 26);
            btnReset.Click += OnReset;
            gb.Controls.Add(btnReset);

            string[] modeNames = { "Linked", "Red", "Green", "Blue" };
            Color[] modeColors = { Color.Gold, Color.Red, Color.Green, Color.Blue };
            int[] modeX = { 86, 170, 246, 322 };
            int[] modeW = { 80, 72, 72, 72 };
            for (int i = 0; i < 4; i++)
            {
                RadioButton rb = new RadioButton();
                rb.Appearance = Appearance.Button;
                rb.Text = modeNames[i];
                rb.Image = MakeDiamond(modeColors[i]);
                rb.ImageAlign = ContentAlignment.MiddleLeft;
                rb.TextAlign = ContentAlignment.MiddleRight;
                rb.Tag = i;
                rb.SetBounds(modeX[i], 266, modeW[i], 26);
                rb.Checked = (i == 0);
                rb.CheckedChanged += OnModeChanged;
                gb.Controls.Add(rb);
                modeBtn[i] = rb;
            }

            Label lblProf = new Label();
            lblProf.Text = "Color profiles:";
            lblProf.Font = bold;
            lblProf.AutoSize = true;
            lblProf.Location = new Point(14, 322);
            Controls.Add(lblProf);

            combo = new ComboBox();
            combo.DropDownStyle = ComboBoxStyle.DropDownList;
            combo.SetBounds(14, 340, 150, 21);
            combo.SelectedIndexChanged += OnComboChanged;
            Controls.Add(combo);

            Label lblHk = new Label();
            lblHk.Text = "Hot-key:";
            lblHk.Font = bold;
            lblHk.AutoSize = true;
            lblHk.Location = new Point(178, 322);
            Controls.Add(lblHk);

            txtHk = new TextBox();
            txtHk.ReadOnly = true;
            txtHk.BackColor = SystemColors.Window;
            txtHk.SetBounds(178, 340, 150, 21);
            txtHk.KeyDown += OnHkKeyDown;
            Controls.Add(txtHk);

            Button btnSave = new Button();
            btnSave.Text = "Save as...";
            btnSave.SetBounds(338, 338, 74, 25);
            btnSave.Click += OnSaveAs;
            Controls.Add(btnSave);

            Button btnDel = new Button();
            btnDel.Text = "Delete";
            btnDel.SetBounds(416, 338, 72, 25);
            btnDel.Click += OnDelete;
            Controls.Add(btnDel);

            Button btnExit = new Button();
            btnExit.Text = "Exit";
            btnExit.SetBounds(14, 400, 90, 28);
            btnExit.Click += OnExitClick;
            Controls.Add(btnExit);

            lblStatus = new Label();
            lblStatus.Text = "Colors10 1.0.0";
            lblStatus.TextAlign = ContentAlignment.MiddleCenter;
            lblStatus.SetBounds(112, 400, 276, 28);
            Controls.Add(lblStatus);

            Button btnHide = new Button();
            btnHide.Text = "Hide";
            btnHide.SetBounds(398, 400, 90, 28);
            btnHide.Click += OnHideClick;
            Controls.Add(btnHide);
        }

        void BuildTray()
        {
            ContextMenu cm = new ContextMenu();
            cm.MenuItems.Add("Show", new EventHandler(OnTrayShow));
            cm.MenuItems.Add("Exit", new EventHandler(OnExitClick));

            notify = new NotifyIcon();
            notify.Icon = appIcon;
            notify.Text = "Colors10";
            notify.ContextMenu = cm;
            notify.DoubleClick += OnTrayShow;
            notify.Visible = true;
        }

        static Icon MakeAppIcon()
        {
            Bitmap bmp = new Bitmap(16, 16);
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.Transparent);
                g.FillRectangle(Brushes.Red, 1, 1, 7, 7);
                g.FillRectangle(Brushes.LimeGreen, 8, 1, 7, 7);
                g.FillRectangle(Brushes.Blue, 1, 8, 7, 7);
                g.FillRectangle(Brushes.Gold, 8, 8, 7, 7);
            }
            return Icon.FromHandle(bmp.GetHicon());
        }

        static Bitmap MakeDiamond(Color c)
        {
            Bitmap bmp = new Bitmap(14, 14);
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.Transparent);
                using (SolidBrush br = new SolidBrush(c))
                {
                    g.FillPolygon(br, new Point[] { new Point(7, 0), new Point(13, 6), new Point(7, 12), new Point(1, 6) });
                }
            }
            return bmp;
        }

        // ---------------------------------------------------------------- values

        void ResetValues()
        {
            for (int ch = 0; ch < 3; ch++)
            {
                gam[ch] = SDef[0];
                bri[ch] = SDef[1];
                con[ch] = SDef[2];
            }
            colorVal = SDef[3];
        }

        static int ClampInt(int v, int lo, int hi)
        {
            if (v < lo) return lo;
            if (v > hi) return hi;
            return v;
        }

        static double Clamp01(double x)
        {
            if (x < 0.0) return 0.0;
            if (x > 1.0) return 1.0;
            return x;
        }

        int CurrentChannel()
        {
            return mode == 0 ? 0 : mode - 1;
        }

        void LoadSliders()
        {
            loading = true;
            int ch = CurrentChannel();
            tb[0].Value = ClampInt(gam[ch], SMin[0], SMax[0]);
            tb[1].Value = ClampInt(bri[ch], SMin[1], SMax[1]);
            tb[2].Value = ClampInt(con[ch], SMin[2], SMax[2]);
            tb[3].Value = ClampInt(colorVal, SMin[3], SMax[3]);
            loading = false;
            UpdateValueLabels();
            graph.Invalidate();
        }

        static string FormatValue(int idx, int v)
        {
            if (idx == 0 || idx == 2)
                return (v / 100.0).ToString("0.00", CultureInfo.InvariantCulture);
            return v.ToString(CultureInfo.InvariantCulture) + "%";
        }

        void UpdateValueLabels()
        {
            for (int i = 0; i < 4; i++)
                valLbl[i].Text = FormatValue(i, tb[i].Value);
        }

        void SetValue(int idx, int v)
        {
            if (idx == 3) { colorVal = v; return; }
            int[] arr = (idx == 0) ? gam : (idx == 1 ? bri : con);
            if (mode == 0)
            {
                for (int ch = 0; ch < 3; ch++) arr[ch] = v;
            }
            else
            {
                arr[mode - 1] = v;
            }
        }

        // ---------------------------------------------------------------- gamma ramp

        double Curve(double x, int ch)
        {
            double c = con[ch] / 100.0;
            double b = bri[ch] / 200.0;
            double g = gam[ch] / 100.0;
            if (g < 0.05) g = 0.05;

            x = (x - 0.5) * c + 0.5 + b;
            x = Clamp01(x);
            x = Math.Pow(x, 1.0 / g);

            return Clamp01(x);
        }

        void RebuildRamp()
        {
            for (int ch = 0; ch < 3; ch++)
            {
                for (int i = 0; i < 256; i++)
                {
                    double v = Curve(i / 255.0, ch) * 65535.0;
                    int iv = (int)Math.Round(v);
                    if (iv < 0) iv = 0;
                    if (iv > 65535) iv = 65535;
                    ramp[ch * 256 + i] = (ushort)iv;
                }
            }
        }

        bool SetRamp(ushort[] data)
        {
            bool ok = false;
            try
            {
                IntPtr dc = Native.GetDC(IntPtr.Zero);
                ok = Native.SetDeviceGammaRamp(dc, data);
                Native.ReleaseDC(IntPtr.Zero, dc);
            }
            catch { ok = false; }
            return ok;
        }

        // Saturation is controlled through AMD ADL2 at driver/display level.
        bool ApplyColorEffect(int v)
        {
            if (saturationController == null) return false;
            return saturationController.SetSaturation(v);
        }

        void ApplyRamp()
        {
            bool ok1 = SetRamp(ramp);
            bool ok2 = ApplyColorEffect(colorVal);

            if (!ok1)
            {
                lblStatus.ForeColor = Color.Firebrick;
                lblStatus.Text = "Rejected by Windows / display driver";
            }
            else if (!ok2)
            {
                lblStatus.ForeColor = Color.Firebrick;
                lblStatus.Text = saturationController == null ? "AMD ADL2 unavailable" : saturationController.Status;
            }
            else
            {
                lblStatus.ForeColor = SystemColors.ControlText;
                lblStatus.Text = saturationController.Status;
            }
        }

        void UpdateAll()
        {
            RebuildRamp();
            ApplyRamp();
            UpdateValueLabels();
            graph.Invalidate();
        }

        // ---------------------------------------------------------------- graph

        void PaintGraph(object sender, PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.Clear(Color.Black);
            int w = graph.ClientSize.Width;
            int h = graph.ClientSize.Height;

            using (Pen grid = new Pen(Color.FromArgb(45, 45, 45)))
            {
                for (int q = 1; q < 4; q++)
                {
                    g.DrawLine(grid, w * q / 4, 0, w * q / 4, h);
                    g.DrawLine(grid, 0, h * q / 4, w, h * q / 4);
                }
            }

            bool same = true;
            for (int i = 0; i < 256; i++)
            {
                if (ramp[i] != ramp[256 + i] || ramp[i] != ramp[512 + i]) { same = false; break; }
            }

            if (same)
            {
                DrawCurve(g, 0, Color.White, 1f);
            }
            else
            {
                Color[] cols = { Color.Red, Color.LimeGreen, Color.DodgerBlue };
                for (int ch = 0; ch < 3; ch++)
                {
                    float width = (mode != 0 && mode - 1 == ch) ? 2f : 1f;
                    DrawCurve(g, ch, cols[ch], width);
                }
            }

            g.DrawString("LUT", Font, Brushes.White, 4, 2);
        }

        void DrawCurve(Graphics g, int ch, Color color, float width)
        {
            int w = graph.ClientSize.Width;
            int h = graph.ClientSize.Height;
            PointF[] pts = new PointF[256];
            for (int i = 0; i < 256; i++)
            {
                float x = i * (w - 1) / 255f;
                float y = (h - 1) - ramp[ch * 256 + i] / 65535f * (h - 1);
                pts[i] = new PointF(x, y);
            }
            using (Pen p = new Pen(color, width))
            {
                g.DrawLines(p, pts);
            }
        }

        // ---------------------------------------------------------------- events

        void OnSlide(object sender, EventArgs e)
        {
            if (loading) return;
            TrackBar t = (TrackBar)sender;
            int idx = (int)t.Tag;
            SetValue(idx, t.Value);
            UpdateAll();
        }

        void OnReset(object sender, EventArgs e)
        {
            ResetValues();
            LoadSliders();
            UpdateAll();
        }

        void OnModeChanged(object sender, EventArgs e)
        {
            RadioButton rb = (RadioButton)sender;
            if (!rb.Checked) return;
            mode = (int)rb.Tag;
            LoadSliders();
        }

        void OnComboChanged(object sender, EventArgs e)
        {
            if (loading) return;
            int i = combo.SelectedIndex;
            if (i >= 0 && i < profiles.Count) ApplyProfile(profiles[i]);
        }

        void OnHideClick(object sender, EventArgs e)
        {
            Hide();
        }

        void OnTrayShow(object sender, EventArgs e)
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        void OnExitClick(object sender, EventArgs e)
        {
            exiting = true;
            Close();
        }

        void OnPowerMode(object sender, PowerModeChangedEventArgs e)
        {
            if (e.Mode == PowerModes.Resume && IsHandleCreated)
                BeginInvoke(new MethodInvoker(ApplyRamp));
        }

        void OnDisplayChanged(object sender, EventArgs e)
        {
            if (IsHandleCreated)
                BeginInvoke(new MethodInvoker(ApplyRamp));
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!exiting && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                return;
            }
            base.OnFormClosing(e);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            SystemEvents.PowerModeChanged -= OnPowerMode;
            SystemEvents.DisplaySettingsChanged -= OnDisplayChanged;
            UnregisterHotkeys();
            if (haveOrig) SetRamp(origRamp);
            try { if (saturationController != null) saturationController.Dispose(); }
            catch { }
            if (notify != null)
            {
                notify.Visible = false;
                notify.Dispose();
            }
            base.OnFormClosed(e);
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            RegisterHotkeys();
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_HOTKEY)
            {
                int idx = m.WParam.ToInt32() - 1;
                if (idx >= 0 && idx < profiles.Count) SelectProfile(idx);
            }
            base.WndProc(ref m);
        }

        // ---------------------------------------------------------------- hot-keys

        static string HotkeyText(uint mods, int vk)
        {
            if (vk == 0) return "";
            StringBuilder sb = new StringBuilder();
            if ((mods & 2) != 0) sb.Append("Ctrl + ");
            if ((mods & 1) != 0) sb.Append("Alt + ");
            if ((mods & 4) != 0) sb.Append("Shift + ");
            string n = ((Keys)vk).ToString();
            if (n.Length == 2 && n[0] == 'D' && char.IsDigit(n[1])) n = n.Substring(1);
            sb.Append(n);
            return sb.ToString();
        }

        void OnHkKeyDown(object sender, KeyEventArgs e)
        {
            e.SuppressKeyPress = true;
            e.Handled = true;
            Keys k = e.KeyCode;

            if (k == Keys.Back || k == Keys.Delete)
            {
                hkMods = 0;
                hkVk = 0;
                txtHk.Text = "";
                return;
            }
            if (k == Keys.ControlKey || k == Keys.Menu || k == Keys.ShiftKey ||
                k == Keys.LWin || k == Keys.RWin) return;

            uint m = 0;
            if (e.Control) m |= 2;
            if (e.Alt) m |= 1;
            if (e.Shift) m |= 4;
            if (m == 0) return; // a modifier is required

            hkMods = m;
            hkVk = (int)k;
            txtHk.Text = HotkeyText(hkMods, hkVk);
        }

        void RegisterHotkeys()
        {
            if (!IsHandleCreated) return;
            UnregisterHotkeys();
            for (int i = 0; i < profiles.Count; i++)
            {
                Profile p = profiles[i];
                if (p.Vk != 0)
                    Native.RegisterHotKey(Handle, i + 1, p.Mods | MOD_NOREPEAT, (uint)p.Vk);
            }
            regCount = profiles.Count;
        }

        void UnregisterHotkeys()
        {
            if (!IsHandleCreated) { regCount = 0; return; }
            for (int i = 1; i <= regCount; i++) Native.UnregisterHotKey(Handle, i);
            regCount = 0;
        }

        // ---------------------------------------------------------------- profiles

        static string ProfilePath()
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Colors10");
            return Path.Combine(dir, "profiles.txt");
        }

        static string IntsToString(int[] a)
        {
            return a[0].ToString(CultureInfo.InvariantCulture) + "," +
                   a[1].ToString(CultureInfo.InvariantCulture) + "," +
                   a[2].ToString(CultureInfo.InvariantCulture);
        }

        static void ParseInts(string s, int[] dest, int lo, int hi)
        {
            string[] parts = s.Split(',');
            for (int i = 0; i < 3; i++)
                dest[i] = ClampInt(int.Parse(parts[i], CultureInfo.InvariantCulture), lo, hi);
        }

        void SaveProfiles()
        {
            try
            {
                string path = ProfilePath();
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                StringBuilder sb = new StringBuilder();
                foreach (Profile p in profiles)
                {
                    sb.Append(p.Name.Replace("\t", " ")).Append('\t');
                    sb.Append(p.Mods.ToString(CultureInfo.InvariantCulture)).Append('\t');
                    sb.Append(p.Vk.ToString(CultureInfo.InvariantCulture)).Append('\t');
                    sb.Append(IntsToString(p.Gam)).Append('\t');
                    sb.Append(IntsToString(p.Bri)).Append('\t');
                    sb.Append(IntsToString(p.Con)).Append('\t');
                    sb.Append(p.Col.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
                }
                File.WriteAllText(path, sb.ToString(), Encoding.UTF8);
            }
            catch { }
        }

        void LoadProfiles()
        {
            profiles.Clear();
            try
            {
                string path = ProfilePath();
                if (!File.Exists(path)) return;
                string[] lines = File.ReadAllLines(path, Encoding.UTF8);
                foreach (string line in lines)
                {
                    if (line.Trim().Length == 0) continue;
                    string[] f = line.Split('\t');
                    if (f.Length < 7) continue;
                    try
                    {
                        Profile p = new Profile();
                        p.Name = f[0];
                        p.Mods = uint.Parse(f[1], CultureInfo.InvariantCulture);
                        p.Vk = int.Parse(f[2], CultureInfo.InvariantCulture);
                        ParseInts(f[3], p.Gam, SMin[0], SMax[0]);
                        ParseInts(f[4], p.Bri, SMin[1], SMax[1]);
                        ParseInts(f[5], p.Con, SMin[2], SMax[2]);
                        p.Col = ClampInt(int.Parse(f[6].Trim(), CultureInfo.InvariantCulture), SMin[3], SMax[3]);
                        profiles.Add(p);
                    }
                    catch { }
                }
            }
            catch { }
        }

        void ReloadCombo(string select)
        {
            loading = true;
            combo.Items.Clear();
            int sel = -1;
            for (int i = 0; i < profiles.Count; i++)
            {
                combo.Items.Add(profiles[i].Name);
                if (select != null && string.Compare(profiles[i].Name, select, true) == 0) sel = i;
            }
            if (sel >= 0) combo.SelectedIndex = sel;
            loading = false;
        }

        void SelectProfile(int idx)
        {
            loading = true;
            combo.SelectedIndex = idx;
            loading = false;
            ApplyProfile(profiles[idx]);
        }

        void ApplyProfile(Profile p)
        {
            for (int ch = 0; ch < 3; ch++)
            {
                gam[ch] = p.Gam[ch];
                bri[ch] = p.Bri[ch];
                con[ch] = p.Con[ch];
            }
            colorVal = p.Col;
            hkMods = p.Mods;
            hkVk = p.Vk;
            txtHk.Text = HotkeyText(hkMods, hkVk);
            LoadSliders();
            UpdateAll();
        }

        void OnSaveAs(object sender, EventArgs e)
        {
            string name = Prompt(this, "Save profile", "Profile name:", combo.Text);
            if (name == null || name.Length == 0) return;

            Profile p = null;
            foreach (Profile q in profiles)
            {
                if (string.Compare(q.Name, name, true) == 0) { p = q; break; }
            }
            if (p == null)
            {
                p = new Profile();
                profiles.Add(p);
            }

            p.Name = name;
            p.Mods = hkMods;
            p.Vk = hkVk;
            for (int ch = 0; ch < 3; ch++)
            {
                p.Gam[ch] = gam[ch];
                p.Bri[ch] = bri[ch];
                p.Con[ch] = con[ch];
            }
            p.Col = colorVal;

            SaveProfiles();
            ReloadCombo(name);
            RegisterHotkeys();
        }

        void OnDelete(object sender, EventArgs e)
        {
            int i = combo.SelectedIndex;
            if (i < 0 || i >= profiles.Count) return;
            profiles.RemoveAt(i);
            SaveProfiles();
            ReloadCombo(null);
            RegisterHotkeys();
            hkMods = 0;
            hkVk = 0;
            txtHk.Text = "";
        }

        static string Prompt(IWin32Window owner, string title, string label, string def)
        {
            Form f = new Form();
            f.Font = new Font("Tahoma", 8.25F);
            f.Text = title;
            f.FormBorderStyle = FormBorderStyle.FixedDialog;
            f.StartPosition = FormStartPosition.CenterParent;
            f.MaximizeBox = false;
            f.MinimizeBox = false;
            f.ShowInTaskbar = false;
            f.ClientSize = new Size(280, 90);

            Label l = new Label();
            l.Text = label;
            l.SetBounds(10, 10, 260, 16);

            TextBox t = new TextBox();
            t.Text = def;
            t.SetBounds(10, 30, 260, 21);

            Button ok = new Button();
            ok.Text = "OK";
            ok.DialogResult = DialogResult.OK;
            ok.SetBounds(114, 58, 75, 24);

            Button cancel = new Button();
            cancel.Text = "Cancel";
            cancel.DialogResult = DialogResult.Cancel;
            cancel.SetBounds(195, 58, 75, 24);

            f.AcceptButton = ok;
            f.CancelButton = cancel;
            f.Controls.Add(l);
            f.Controls.Add(t);
            f.Controls.Add(ok);
            f.Controls.Add(cancel);

            string result = null;
            if (f.ShowDialog(owner) == DialogResult.OK) result = t.Text.Trim();
            f.Dispose();
            return result;
        }
    }
}
