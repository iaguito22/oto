using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;

[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8")]

internal sealed class Setup
{
    private const string UpgradeCode = "{8F45E92A-3C74-4B2E-9A81-56E8049A1201}";
    private static readonly Assembly Package = typeof(Setup).Assembly;
    private readonly Window window;
    private readonly bool preview;
    private readonly Version version;
    private readonly Button primary;
    private readonly Button secondary;
    private readonly TextBox folderBox;
    private string folder;
    private string previousInstallFolder;
    private string logPath;
    private bool choosingFolder;
    private bool installing;
    private bool finished;

    [DllImport("msi.dll", CharSet = CharSet.Unicode)]
    private static extern uint MsiEnumRelatedProducts(string upgradeCode, uint reserved, uint index, StringBuilder productCode);

    [DllImport("msi.dll", CharSet = CharSet.Unicode)]
    private static extern uint MsiGetProductInfo(string productCode, string property, StringBuilder value, ref uint length);

    [STAThread]
    private static void Main(string[] args)
    {
        try
        {
            var app = new Application();
            var setup = new Setup();
            if (setup.preview && args.Length == 2 && args[0] == "--capture")
                setup.window.ContentRendered += (sender, eventArgs) => setup.Capture(args[1]);
            app.Run(setup.window);
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Instalador de Oto", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private Setup()
    {
        preview = Package.GetManifestResourceInfo("OtoPayload") == null;
        using (var stream = Resource("OtoVersion"))
        using (var reader = new StreamReader(stream))
            version = Version.Parse(reader.ReadToEnd().Trim());
        using (var stream = Resource("OtoLayout"))
            window = (Window)XamlReader.Load(stream);

        var art = new BitmapImage();
        using (var stream = Resource("OtoShowcase"))
        {
            art.BeginInit();
            art.CacheOption = BitmapCacheOption.OnLoad;
            art.StreamSource = stream;
            art.EndInit();
        }
        art.Freeze();
        Get<ImageBrush>("Showcase").ImageSource = art;
        primary = Get<Button>("PrimaryButton");
        secondary = Get<Button>("SecondaryButton");
        folderBox = Get<TextBox>("FolderBox");
        folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Oto Desktop");
        Get<TextBlock>("VersionLabel").Text = preview ? "Vista previa · " + version : "Versión " + version;
        Get<Button>("CloseButton").Click += (sender, args) => window.Close();
        Get<Button>("BrowseButton").Click += (sender, args) => Browse();
        Get<Grid>("TitleBar").MouseLeftButtonDown += (sender, args) => window.DragMove();
        window.Closing += (sender, args) => args.Cancel = installing;
        window.PreviewKeyDown += (sender, args) =>
        {
            if (args.Key != Key.Escape || installing) return;
            args.Handled = true;
            if (choosingFolder) ShowFolder(false);
            else window.Close();
        };
        primary.Click += async (sender, args) => await HandlePrimary();
        secondary.Click += (sender, args) => HandleSecondary();
        if (!preview)
            ReadInstalledVersion();
    }

    private static Stream Resource(string name)
    {
        return Package.GetManifestResourceStream(name);
    }

    private T Get<T>(string name) where T : class
    {
        return (T)window.FindName(name);
    }

    private static string ProductInfo(string code, string property)
    {
        var value = new StringBuilder(512);
        uint length = (uint)value.Capacity;
        return MsiGetProductInfo(code, property, value, ref length) == 0 ? value.ToString() : "";
    }

    private void ReadInstalledVersion()
    {
        Version latest = null;
        for (uint index = 0; ; index++)
        {
            var code = new StringBuilder(39);
            if (MsiEnumRelatedProducts(UpgradeCode, 0, index, code) != 0)
                break;
            Version installed;
            if (!Version.TryParse(ProductInfo(code.ToString(), "VersionString"), out installed) ||
                (latest != null && installed <= latest))
                continue;
            latest = installed;
            var location = ProductInfo(code.ToString(), "InstallLocation");
            if (!String.IsNullOrWhiteSpace(location))
                folder = previousInstallFolder = location.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        }
        if (latest == null) return;
        if (latest >= version)
        {
            ShowFinished("Oto ya está instalado", "Tienes la versión " + latest + ".", false);
            return;
        }
        SetCopy("Actualiza Oto", "Se conservarán tus listas, descargas y ajustes.");
        primary.Content = "Actualizar Oto";
    }

    private void SetCopy(string heading, string description)
    {
        Get<TextBlock>("Heading").Text = heading;
        Get<TextBlock>("Description").Text = description;
    }

    private void ShowFolder(bool show)
    {
        choosingFolder = show;
        Get<Border>("Artwork").Visibility = show ? Visibility.Collapsed : Visibility.Visible;
        Get<Border>("FolderPanel").Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        Get<TextBlock>("FolderError").Text = "";
        primary.Content = show ? "Guardar carpeta" : installLabel;
        secondary.Content = show ? "Volver" : "Elegir carpeta";
        if (show)
        {
            folderBox.Text = folder;
            folderBox.Focus();
        }
    }

    private string installLabel = "Instalar Oto";

    private void HandleSecondary()
    {
        if (finished)
        {
            window.Close();
            return;
        }
        if (logPath != null && File.Exists(logPath))
        {
            Process.Start(new ProcessStartInfo(logPath) { UseShellExecute = true });
            return;
        }
        if (!choosingFolder) installLabel = (string)primary.Content;
        ShowFolder(!choosingFolder);
    }

    private void Browse()
    {
        using (var picker = new System.Windows.Forms.FolderBrowserDialog())
        {
            picker.Description = "Elige dónde instalar Oto.";
            if (Directory.Exists(folderBox.Text)) picker.SelectedPath = folderBox.Text;
            if (picker.ShowDialog() == System.Windows.Forms.DialogResult.OK)
                folderBox.Text = picker.SelectedPath;
        }
    }

    private void SaveFolder()
    {
        try
        {
            var input = folderBox.Text.Trim();
            if (!Path.IsPathRooted(input) || input.Length < 4 || input[1] != ':' ||
                (input[2] != '\\' && input[2] != '/') || input.IndexOf('"') >= 0)
                throw new ArgumentException();
            var path = Path.GetFullPath(input).TrimEnd(Path.DirectorySeparatorChar);
            if (path.Length < 4 || File.Exists(path))
                throw new ArgumentException();

            if (!String.Equals(path, previousInstallFolder, StringComparison.OrdinalIgnoreCase) && Directory.Exists(path))
            {
                using (var contents = Directory.EnumerateFileSystemEntries(path).GetEnumerator())
                {
                    if (contents.MoveNext())
                    {
                        Get<TextBlock>("FolderError").Text = "Esta carpeta contiene archivos. Elige una carpeta vacía para Oto.";
                        return;
                    }
                }
            }
            folder = path;
            ShowFolder(false);
        }
        catch (Exception error)
        {
            if (!(error is ArgumentException || error is NotSupportedException || error is IOException || error is UnauthorizedAccessException))
                throw;
            Get<TextBlock>("FolderError").Text = "Elige una carpeta válida en una unidad local.";
        }
    }

    private async Task HandlePrimary()
    {
        if (choosingFolder) { SaveFolder(); return; }
        if (finished)
        {
            if (!preview && (string)primary.Content == "Abrir Oto" && File.Exists(AppPath))
                Process.Start(new ProcessStartInfo(AppPath) { WorkingDirectory = folder, UseShellExecute = true });
            window.Close();
            return;
        }
        if (!preview && IsOtoRunning())
        {
            SetCopy("Oto está abierto", "Sal de Oto desde la bandeja y vuelve a intentarlo.");
            return;
        }

        installLabel = (string)primary.Content;
        installing = true;
        Get<Button>("CloseButton").IsEnabled = primary.IsEnabled = secondary.IsEnabled = false;
        Get<Border>("Artwork").Visibility = Visibility.Collapsed;
        Get<Border>("ProgressPanel").Visibility = Visibility.Visible;
        Get<TextBlock>("ProgressLabel").Text = preview ? "Simulando la instalación…" : "Preparando el paquete de Oto…";
        SetCopy(preview ? "Vista previa" : "Instalando Oto", "Puedes esperar en esta ventana.");
        try
        {
            int result = 0;
            if (preview) await Task.Delay(1100);
            else result = await Task.Run(() => Install());

            if (result == 0 || result == 3010 || result == 1641)
            {
                ShowFinished(preview ? "Así se vería al terminar" : "Oto está listo",
                    preview ? "Vista previa completada. Tu instalación sigue igual."
                            : (result == 0 ? "Ya puedes abrir Oto." : "Reinicia Windows para completar la instalación."),
                    result != 0);
            }
            else
            {
                SetCopy("No se pudo instalar Oto", result == 1618 ? "Hay otra instalación de Windows en curso. Inténtalo cuando termine."
                    : "Windows devolvió el código " + result + ". Puedes consultar el registro o reintentar.");
                primary.Content = "Reintentar";
                secondary.Content = File.Exists(logPath) ? "Ver registro" : "Elegir carpeta";
            }
        }
        catch (Exception error)
        {
            SetCopy("No se pudo instalar Oto", error.Message);
            primary.Content = "Reintentar";
            secondary.Content = File.Exists(logPath) ? "Ver registro" : "Elegir carpeta";
        }
        finally
        {
            installing = false;
            Get<Button>("CloseButton").IsEnabled = primary.IsEnabled = secondary.IsEnabled = true;
            Get<Border>("ProgressPanel").Visibility = Visibility.Collapsed;
            Get<Border>("Artwork").Visibility = Visibility.Visible;
        }
    }

    private string AppPath { get { return Path.Combine(folder, "Oto.exe"); } }

    private static bool IsOtoRunning()
    {
        var processes = Process.GetProcessesByName("Oto");
        foreach (var process in processes) process.Dispose();
        return processes.Length != 0;
    }

    private void ShowFinished(string heading, string description, bool restartRequired)
    {
        finished = true;
        SetCopy(heading, description);
        primary.Content = preview || restartRequired || !File.Exists(AppPath) ? "Cerrar" : "Abrir Oto";
        secondary.Content = "Cerrar";
        secondary.Visibility = (string)primary.Content == "Cerrar" ? Visibility.Collapsed : Visibility.Visible;
    }

    private int Install()
    {
        var work = Path.Combine(Path.GetTempPath(), "OtoSetup-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(work);
        var msi = Path.Combine(work, "Oto.msi");
        try
        {
            using (var payload = Resource("OtoPayload"))
            using (var file = new FileStream(msi, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                payload.CopyTo(file);
            var logs = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Oto Setup", "logs");
            Directory.CreateDirectory(logs);
            logPath = Path.Combine(logs, DateTime.UtcNow.ToString("yyyyMMdd-HHmmss-fff") + ".log");
            window.Dispatcher.Invoke(new Action(() => Get<TextBlock>("ProgressLabel").Text = "Windows está instalando Oto…"));
            var arguments = "/i \"" + msi + "\" INSTALLDIR=\"" + folder + "\" /qn /norestart /L*v \"" + logPath + "\"";
            var system = Environment.GetFolderPath(Environment.SpecialFolder.System);
            using (var process = Process.Start(new ProcessStartInfo(Path.Combine(system, "msiexec.exe"), arguments)
            {
                UseShellExecute = false,
                CreateNoWindow = true
            }))
            {
                process.WaitForExit();
                return process.ExitCode;
            }
        }
        finally
        {
            try { File.Delete(msi); Directory.Delete(work); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private void Capture(string path)
    {
        var dpi = VisualTreeHelper.GetDpi(window);
        var bitmap = new RenderTargetBitmap((int)Math.Ceiling(window.ActualWidth * dpi.DpiScaleX),
            (int)Math.Ceiling(window.ActualHeight * dpi.DpiScaleY), dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
        bitmap.Render(window);
        var png = new PngBitmapEncoder();
        png.Frames.Add(BitmapFrame.Create(bitmap));
        using (var stream = File.Create(path))
            png.Save(stream);
    }
}
