/*
 * yt-dlp Studio v5 (Java / Swing + FlatLaf)
 * ------------------------------------------------------------------
 * 上部タブ式のシンプルなUI。Python版(v5)からの全面移植 + UI刷新。
 *  - 上部タブ（ダウンロード / 履歴 / 設定 / 情報）
 *  - カード型ダウンロードキュー（ジョブごとに進捗バー）
 *  - 並列ダウンロード（設定で1〜5）
 *  - 画質/音質を選べる形式選択（MP4/MKV/MP3/WAV/FLAC/AAC/Opus/M4A）
 *  - 字幕/メタデータ/チャプター/サムネ埋め込み
 *  - ファイル名はプリセットから選択（読みやすいプレビュー付き）
 *  - 一般/ダウンロード/ネットワーク/詳細にグループ化した設定
 *  - クリップボード監視・履歴（検索可）・テーマ即時切替（ダーク/ライト/自動）
 *  - yt-dlp 更新・完了通知（システムトレイ）
 *  - 文字化け対策: yt-dlp に --encoding utf-8、UTF-8 で読み取り
 *
 * 外部ツール: yt-dlp.exe（jarと同じフォルダ）/ ffmpeg.exe（設定 or 自動検出）
 * 依存: lib/flatlaf-3.7.1.jar, lib/gson-2.11.0.jar
 */

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatClientProperties;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;

public class YtDlpStudio extends JFrame {

    // ── 定数・配色 ───────────────────────────────
    static final String APP_TITLE   = "yt-dlp Studio";
    static final String APP_VERSION = "5.1.0 (Java)";

    static final Color ACCENT  = new Color(0x7c5cff);
    static final Color SUCCESS = new Color(0x22c55e);
    static final Color WARNING = new Color(0xf59e0b);
    static final Color DANGER  = new Color(0xef4444);
    static final Color INFO    = new Color(0x3b82f6);
    static final Color SUBTLE  = new Color(0x9aa0a6);

    static final String ST_WAITING = "待機中";
    static final String ST_RUNNING = "ダウンロード中";
    static final String ST_DONE    = "完了";
    static final String ST_ERROR   = "エラー";
    static final String ST_STOPPED = "停止";

    static Font F (int sz){ return new Font("Meiryo", Font.PLAIN, sz); }
    static Font FB(int sz){ return new Font("Meiryo", Font.BOLD,  sz); }
    static final Font F_NORMAL = F(13), F_SMALL = F(11), F_BOLD = FB(13),
                      F_TITLE = FB(19), F_H2 = FB(15), F_MONO = new Font("Consolas", Font.PLAIN, 12);

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    static final LinkedHashMap<String,Integer> VIDEO_QUALITY = new LinkedHashMap<>();
    static final String[] VIDEO_CONTAINER = {"MP4","MKV"};
    static final String[] AUDIO_FORMAT = {"MP3","WAV","FLAC","AAC","Opus","M4A"};
    static final Set<String> AUDIO_LOSSLESS = new HashSet<>(Arrays.asList("WAV","FLAC"));
    static final LinkedHashMap<String,String> AUDIO_QUALITY = new LinkedHashMap<>();
    static final LinkedHashMap<String,String> FILENAME_PRESETS = new LinkedHashMap<>();
    static {
        VIDEO_QUALITY.put("最高画質", null);
        VIDEO_QUALITY.put("1080p", 1080);
        VIDEO_QUALITY.put("720p", 720);
        VIDEO_QUALITY.put("480p", 480);
        VIDEO_QUALITY.put("360p", 360);
        AUDIO_QUALITY.put("最高","0");
        AUDIO_QUALITY.put("320k","320K");
        AUDIO_QUALITY.put("256k","256K");
        AUDIO_QUALITY.put("192k","192K");
        AUDIO_QUALITY.put("128k","128K");
        FILENAME_PRESETS.put("タイトル", "%(title)s.%(ext)s");
        FILENAME_PRESETS.put("投稿者 - タイトル", "%(uploader)s - %(title)s.%(ext)s");
        FILENAME_PRESETS.put("番号. タイトル（プレイリスト向け）", "%(playlist_index)s. %(title)s.%(ext)s");
        FILENAME_PRESETS.put("日付 - タイトル", "%(upload_date)s - %(title)s.%(ext)s");
        FILENAME_PRESETS.put("カスタム", null);
    }

    static final Object[][] ERROR_PATTERNS = {
        {"This video is unavailable", "動画が利用不可（非公開・削除済みなど）"},
        {"Sign in to confirm your age", "年齢確認が必要（設定でCookie使用を有効に）"},
        {"confirm you.?re not a bot|Sign in to confirm you", "Botと判定されました（Cookie使用を有効に）"},
        {"Private video", "非公開動画です"},
        {"members.?only", "メンバー限定動画です（Cookie使用を有効に）"},
        {"Video unavailable", "視聴不可（地域制限などの可能性）"},
        {"Unable to download webpage|getaddrinfo|Failed to resolve", "ネットワークエラー（接続を確認）"},
        {"HTTP Error 4\\d\\d", "HTTP 4xxエラー（URLを確認）"},
        {"HTTP Error 5\\d\\d", "HTTP 5xxエラー（サーバー側の問題）"},
        {"ffmpeg.*not found|ffmpeg.*cannot|ffprobe.*not found", "ffmpegが見つかりません（設定で確認）"},
        {"No video formats found|Requested format is not available", "対応フォーマットが見つかりません"},
        {"Postprocessing:", "変換処理（ffmpeg）でエラー"},
        {"\\[generic\\]", "未対応URL、またはURLが無効です"},
        {"Unsupported URL", "未対応のURLです"},
    };

    static final Pattern P_PROGRESS = Pattern.compile(
        "\\[download\\]\\s+([\\d.]+)%.*?at\\s+([\\d.]+\\s*\\S+)\\s+ETA\\s+(\\S+)");
    static final Pattern P_DEST = Pattern.compile(
        "\\[(?:download|Merger|ExtractAudio|ffmpeg|VideoConvertor)\\]\\s+Destination:\\s+(.+)");
    static final Pattern P_ALREADY = Pattern.compile(
        "\\[download\\] (.+) has already been downloaded");

    static final File BASE_DIR = computeBase();
    static File computeBase(){
        // jpackage 製 exe から起動した場合は exe のあるフォルダを基準にする
        try {
            String appPath = System.getProperty("jpackage.app-path");
            if (appPath != null && !appPath.isEmpty()) {
                File dir = new File(appPath).getParentFile();
                if (dir != null) return dir;
            }
        } catch (Exception ignored) {}
        try {
            File f = new File(YtDlpStudio.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return f.isFile() ? f.getParentFile() : f;
        } catch (Exception e){ return new File(System.getProperty("user.dir")); }
    }

    // ── アプリアイコン ───────────────────────────
    static final List<Image> APP_ICONS = loadAppIcons();
    static BufferedImage loadAppImage(){
        // 1) jar 内のリソース（ビルド時に out/app.png として同梱）
        try (InputStream in = YtDlpStudio.class.getResourceAsStream("/app.png")) {
            if (in != null) return javax.imageio.ImageIO.read(in);
        } catch (Exception ignored) {}
        // 2) exe/jar と同じフォルダ、または src 配下のファイル
        for (String n : new String[]{"app.png", "src/app.png"}) {
            File f = new File(BASE_DIR, n);
            if (f.exists()) { try { return javax.imageio.ImageIO.read(f); } catch (Exception ignored) {} }
        }
        return null;
    }
    static List<Image> loadAppIcons(){
        List<Image> list = new ArrayList<>();
        BufferedImage base = loadAppImage();
        if (base != null)
            for (int s : new int[]{16,20,24,32,48,64,128,256})
                list.add(base.getScaledInstance(s, s, Image.SCALE_SMOOTH));
        return list;
    }

    // ── 設定 ─────────────────────────────────────
    static class Hist { String dt, url, title, fmt, result; }
    static class Config {
        String outdir = BASE_DIR.getPath();
        String media = "動画";
        String videoQuality = "最高画質";
        String videoContainer = "MP4";
        String audioFormat = "MP3";
        String audioQuality = "最高";
        boolean playlist = false, thumbnail = true, subs = false,
                metadata = true, chapters = false, cookies = false;
        String speedLimit = "", proxy = "", extraArgs = "";
        String filenamePreset = "タイトル";
        String filenameCustom = "%(title)s.%(ext)s";
        String cookieBrowser = "chrome";
        String overwrite = "スキップ";
        String subLang = "ja,en";
        String ffmpegPath = "";
        boolean notify = true, openAfter = false, confirmExit = true,
                clipWatch = false, autoUpdate = false;
        int maxParallel = 3;
        String theme = "system";
        List<Hist> history = new ArrayList<>();

        static File file(){ return new File(BASE_DIR, "yt_dlp_studio_java.json"); }
        static Config load(){
            File f = file();
            if (f.exists()) {
                try (Reader r = new InputStreamReader(new FileInputStream(f), UTF8)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) { if (c.history == null) c.history = new ArrayList<>(); return c; }
                } catch (Exception ignored) {}
            }
            return new Config();
        }
        void save(){
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file()), UTF8)) {
                GSON.toJson(this, w);
            } catch (Exception ignored) {}
        }
    }

    // ── 形式 → 引数 ──────────────────────────────
    static class Fmt { List<String> args; String ext, label, mediaKey; }
    static Fmt buildFormat(String media, String vq, String vc, String af, String aq){
        Fmt r = new Fmt(); r.args = new ArrayList<>();
        if (media.equals("動画")) {
            Integer h = VIDEO_QUALITY.get(vq);
            String hf = (h != null) ? "[height<=?" + h + "]" : "";
            if (vc.equals("MP4")) {
                String f = "bv*"+hf+"[vcodec^=avc1]+ba[ext=m4a]/bv*"+hf+"[vcodec^=avc1]+ba/bv*"+hf+"+ba/b"+hf+"/b";
                r.args.add("-f"); r.args.add(f);
                r.args.add("--merge-output-format"); r.args.add("mp4");
                r.ext = "mp4";
            } else {
                String f = "bv*"+hf+"+ba/b"+hf+"/b";
                r.args.add("-f"); r.args.add(f);
                r.args.add("--merge-output-format"); r.args.add("mkv");
                r.ext = "mkv";
            }
            r.label = vc + " " + vq; r.mediaKey = "video";
        } else {
            r.args.add("-f"); r.args.add("ba/b");
            r.args.add("-x"); r.args.add("--audio-format"); r.args.add(af.toLowerCase());
            if (!AUDIO_LOSSLESS.contains(af)) {
                r.args.add("--audio-quality"); r.args.add(AUDIO_QUALITY.getOrDefault(aq, "0"));
            }
            r.label = AUDIO_LOSSLESS.contains(af) ? af : af + " " + aq;
            r.ext = af.toLowerCase(); r.mediaKey = "audio";
        }
        return r;
    }

    static String analyzeError(List<String> log){
        String full = String.join("\n", log);
        for (Object[] pm : ERROR_PATTERNS)
            if (Pattern.compile((String) pm[0], Pattern.CASE_INSENSITIVE).matcher(full).find())
                return (String) pm[1];
        for (int i = log.size()-1; i >= 0; i--)
            if (log.get(i).contains("ERROR"))
                return log.get(i).length() > 160 ? log.get(i).substring(0,160) : log.get(i);
        return "不明なエラー（ログを確認してください）";
    }

    static List<String> splitArgs(String s){
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(s);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
        return out;
    }

    static String ytDlpPath(){
        File f = new File(BASE_DIR, "yt-dlp.exe");
        return f.exists() ? f.getPath() : "yt-dlp.exe";
    }
    static String ffmpegPath(Config c){
        if (c != null && c.ffmpegPath != null && !c.ffmpegPath.isEmpty()) {
            File p = new File(c.ffmpegPath);
            if (p.isDirectory() && new File(p, "ffmpeg.exe").exists()) return p.getPath();
            if (p.isFile() && p.getName().equalsIgnoreCase("ffmpeg.exe")) return p.getParent();
        }
        File[] cands = {
            new File(BASE_DIR, "ffmpeg\\bin"), new File(BASE_DIR, "ffmpeg"), BASE_DIR,
            new File("C:\\Users\\amake\\Desktop\\Tool\\ffmpeg\\bin")
        };
        for (File d : cands) if (new File(d, "ffmpeg.exe").exists()) return d.getPath();
        return "";
    }

    // ── ジョブ ───────────────────────────────────
    static class Job {
        static int counter = 0;
        final int id;
        String url, ext, label, mediaKey, outdir;
        List<String> args;
        boolean playlist, thumbnail, subs, metadata, chapters, cookies;
        String filenameTmpl;
        volatile String title = "", filepath = "", status = ST_WAITING, speed = "", eta = "", error = "";
        volatile double progress = 0;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        volatile Process process;
        volatile Future<?> future;
        JobCard card;

        Job(String url, Fmt f, String outdir){
            this.id = ++counter; this.url = url;
            this.args = f.args; this.ext = f.ext; this.label = f.label; this.mediaKey = f.mediaKey;
            this.outdir = outdir;
        }
        String displayName(){ return title.isEmpty() ? url : title; }

        List<String> buildCmd(Config cfg){
            List<String> cmd = new ArrayList<>();
            cmd.add(ytDlpPath());
            String ff = ffmpegPath(cfg);
            if (!ff.isEmpty()) { cmd.add("--ffmpeg-location"); cmd.add(ff); }
            cmd.addAll(args);
            if (thumbnail) cmd.add("--embed-thumbnail");
            if (metadata)  cmd.add("--embed-metadata");
            if (chapters)  cmd.add("--embed-chapters");
            if (subs && mediaKey.equals("video")) {
                cmd.add("--embed-subs"); cmd.add("--sub-langs");
                cmd.add(cfg.subLang == null || cfg.subLang.trim().isEmpty() ? "all" : cfg.subLang.trim());
            }
            if (!playlist) cmd.add("--no-playlist");
            if (cookies)   { cmd.add("--cookies-from-browser"); cmd.add(cfg.cookieBrowser); }
            if ("上書き".equals(cfg.overwrite)) cmd.add("--force-overwrites");
            else cmd.add("--no-overwrites");
            if (!cfg.speedLimit.isEmpty()) { cmd.add("-r"); cmd.add(cfg.speedLimit); }
            if (!cfg.proxy.isEmpty())      { cmd.add("--proxy"); cmd.add(cfg.proxy); }
            String tmpl = (filenameTmpl == null || filenameTmpl.isEmpty()) ? "%(title)s.%(ext)s" : filenameTmpl;
            cmd.add("-o"); cmd.add(new File(outdir, tmpl).getPath());
            cmd.add("--newline"); cmd.add("--ignore-config"); cmd.add("--encoding"); cmd.add("utf-8");
            if (!cfg.extraArgs.isEmpty()) cmd.addAll(splitArgs(cfg.extraArgs));
            cmd.add(url);
            return cmd;
        }
    }

    // ── フィールド ───────────────────────────────
    Config cfg;
    ExecutorService pool = Executors.newCachedThreadPool();
    volatile Semaphore sem;
    final List<Job> jobs = new ArrayList<>();
    final Map<Integer,JobCard> cards = new HashMap<>();
    TrayIcon trayIcon;

    JTabbedPane tabs;
    JComboBox<String> cbTheme;
    JTextArea urlArea, logArea;
    JComboBox<String> cbMedia, cbVQ, cbVC, cbAF, cbAQ;
    JCheckBox ckPlaylist, ckThumb, ckSubs, ckMeta, ckChapters, ckCookies, swClip;
    JTextField tfOutdir, tfSpeed, tfProxy, tfExtra, tfFfmpeg, tfCustomName, tfSubLang, tfHistSearch;
    JComboBox<String> cbParallel, cbOverwrite, cbCookieBrowser, cbFilePreset;
    JCheckBox ckNotify, ckOpenAfter, ckConfirmExit, ckAutoUpdate;
    JLabel lbPreview, lbFfmpegStatus, lbVersion, lbUpdateStatus;
    JPanel queuePanel, histPanel;
    JLabel emptyLabel;
    javax.swing.Timer clipTimer;
    String lastClip = "";

    YtDlpStudio(Config cfg){
        this.cfg = cfg;
        this.sem = new Semaphore(Math.max(1, cfg.maxParallel));
        setTitle(APP_TITLE);
        if (!APP_ICONS.isEmpty()) setIconImages(APP_ICONS);
        setSize(1080, 760);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter(){
            public void windowClosing(java.awt.event.WindowEvent e){ onClose(); }
        });

        setLayout(new BorderLayout());
        add(buildTabs(), BorderLayout.CENTER);

        onMediaChange(); onPresetChange(); updatePreview(); updateFfmpegStatus();
        if (swClip.isSelected()) startClip();
        fetchVersion();
        if (cfg.autoUpdate) SwingUtilities.invokeLater(this::updateYtDlp);
    }

    // ── タブ ─────────────────────────────────────
    JComponent buildTabs(){
        tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(FB(14));
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, 46);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_INSETS, new Insets(6,18,6,18));

        // 左上ブランド
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        brand.setBorder(new EmptyBorder(0,14,0,16));
        JLabel b1 = new JLabel("yt-dlp"); b1.setFont(F_TITLE); b1.setForeground(ACCENT);
        JLabel b2 = new JLabel(" Studio"); b2.setFont(F_TITLE); b2.setForeground(SUBTLE);
        brand.add(b1); brand.add(b2);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_LEADING_COMPONENT, brand);

        // 右上：テーマ + バージョン
        JPanel trail = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        cbTheme = new JComboBox<>(new String[]{"ダーク","ライト","自動"});
        cbTheme.setSelectedItem(cfg.theme.equals("dark")?"ダーク":cfg.theme.equals("light")?"ライト":"自動");
        cbTheme.addActionListener(e -> {
            String v = (String) cbTheme.getSelectedItem();
            applyTheme(v.equals("ダーク")?"dark":v.equals("ライト")?"light":"system");
        });
        lbVersion = new JLabel("yt-dlp: …"); lbVersion.setFont(F_SMALL); lbVersion.setForeground(SUBTLE);
        trail.add(lbVersion); trail.add(cbTheme);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, trail);

        tabs.addTab("ダウンロード", buildDownloadPage());
        tabs.addTab("履歴", buildHistoryPage());
        tabs.addTab("設定", buildSettingsPage());
        tabs.addTab("情報", buildInfoPage());
        tabs.addChangeListener(e -> { if (tabs.getSelectedIndex()==1) renderHistory(); });
        return tabs;
    }

    // ── 共通UI ───────────────────────────────────
    JPanel card(){
        JPanel p = new JPanel();
        p.putClientProperty(FlatClientProperties.STYLE, "arc:16; border:1,1,1,1,$Component.borderColor");
        return p;
    }
    JButton primaryBtn(String text){
        JButton b = new JButton(text);
        b.setFont(F_BOLD); b.setFocusPainted(false);
        b.putClientProperty(FlatClientProperties.STYLE,
            "arc:10; borderWidth:0; focusWidth:0; background:#7c5cff; foreground:#ffffff");
        return b;
    }
    JButton ghostBtn(String text){
        JButton b = new JButton(text);
        b.setFont(F_SMALL); b.setFocusPainted(false);
        b.putClientProperty(FlatClientProperties.STYLE, "arc:8; focusWidth:0");
        return b;
    }
    JLabel small(String t){ JLabel l=new JLabel(t); l.setFont(F_SMALL); l.setForeground(SUBTLE); return l; }

    // ── ダウンロードページ ───────────────────────
    JComponent buildDownloadPage(){
        JPanel p = new JPanel(new BorderLayout(0,10));
        p.setBorder(new EmptyBorder(16,18,14,18));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        // URL カード
        JPanel urlCard = card();
        urlCard.setLayout(new BorderLayout(8,8));
        urlCard.setBorder(new EmptyBorder(12,14,12,14));
        urlCard.setAlignmentX(LEFT_ALIGNMENT);
        JPanel uHead = new JPanel(new BorderLayout()); uHead.setOpaque(false);
        JLabel uTitle = new JLabel("URL（複数行で一括追加できます）"); uTitle.setFont(F_BOLD);
        uHead.add(uTitle, BorderLayout.WEST);
        swClip = new JCheckBox("クリップボード監視", cfg.clipWatch);
        swClip.setOpaque(false); swClip.setFont(F_SMALL);
        swClip.addActionListener(e -> { if (swClip.isSelected()) startClip(); else stopClip(); });
        uHead.add(swClip, BorderLayout.EAST);
        urlCard.add(uHead, BorderLayout.NORTH);

        urlArea = new JTextArea(3, 10); urlArea.setLineWrap(false);
        JScrollPane usp = new JScrollPane(urlArea);
        usp.setPreferredSize(new Dimension(10, 76));
        urlCard.add(usp, BorderLayout.CENTER);

        JPanel uBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); uBtns.setOpaque(false);
        JButton bPaste = ghostBtn("貼り付け"); bPaste.addActionListener(e -> pasteUrl());
        JButton bClear = ghostBtn("クリア"); bClear.addActionListener(e -> urlArea.setText(""));
        uBtns.add(bPaste); uBtns.add(bClear);
        urlCard.add(uBtns, BorderLayout.SOUTH);
        top.add(urlCard);
        top.add(Box.createVerticalStrut(10));

        // 形式カード
        JPanel opt = card();
        opt.setLayout(new GridBagLayout());
        opt.setBorder(new EmptyBorder(14,14,14,14));
        opt.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2,5,2,5); g.anchor = GridBagConstraints.WEST;

        cbMedia = new JComboBox<>(new String[]{"動画","音声"}); cbMedia.setSelectedItem(cfg.media);
        cbVQ = new JComboBox<>(VIDEO_QUALITY.keySet().toArray(new String[0])); cbVQ.setSelectedItem(cfg.videoQuality);
        cbVC = new JComboBox<>(VIDEO_CONTAINER); cbVC.setSelectedItem(cfg.videoContainer);
        cbAF = new JComboBox<>(AUDIO_FORMAT); cbAF.setSelectedItem(cfg.audioFormat);
        cbAQ = new JComboBox<>(AUDIO_QUALITY.keySet().toArray(new String[0])); cbAQ.setSelectedItem(cfg.audioQuality);
        cbMedia.addActionListener(e -> onMediaChange());
        cbAF.addActionListener(e -> onMediaChange());

        String[] labels = {"種類","画質","コンテナ","音声形式","音質"};
        JComboBox[] combos = {cbMedia,cbVQ,cbVC,cbAF,cbAQ};
        for (int i=0;i<labels.length;i++){
            g.gridx=i; g.gridy=0; opt.add(small(labels[i]), g);
            g.gridx=i; g.gridy=1; opt.add(combos[i], g);
        }
        g.gridx=labels.length; g.gridy=1; g.weightx=1; opt.add(new JLabel(), g); g.weightx=0;

        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); checks.setOpaque(false);
        ckPlaylist = mkCheck("プレイリスト全体", cfg.playlist);
        ckThumb    = mkCheck("サムネ埋込", cfg.thumbnail);
        ckSubs     = mkCheck("字幕埋込", cfg.subs);
        ckMeta     = mkCheck("メタデータ", cfg.metadata);
        ckChapters = mkCheck("チャプター", cfg.chapters);
        ckCookies  = mkCheck("Cookie使用", cfg.cookies);
        for (JCheckBox c : new JCheckBox[]{ckPlaylist,ckThumb,ckSubs,ckMeta,ckChapters,ckCookies}){
            checks.add(c); checks.add(Box.createHorizontalStrut(12));
        }
        g.gridx=0; g.gridy=2; g.gridwidth=6; g.insets=new Insets(10,5,4,5);
        opt.add(checks, g);

        JPanel dir = new JPanel(new BorderLayout(8,0)); dir.setOpaque(false);
        dir.add(small("保存先 "), BorderLayout.WEST);
        tfOutdir = new JTextField(cfg.outdir);
        dir.add(tfOutdir, BorderLayout.CENTER);
        JPanel dirBtns = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); dirBtns.setOpaque(false);
        JButton bBrowse = ghostBtn("参照"); bBrowse.addActionListener(e -> browseDir());
        JButton bOpen = ghostBtn("開く"); bOpen.addActionListener(e -> openOutdir());
        dirBtns.add(bBrowse); dirBtns.add(bOpen);
        dir.add(dirBtns, BorderLayout.EAST);
        g.gridy=3; opt.add(dir, g);

        JButton addBtn = primaryBtn("＋  キューに追加してダウンロード");
        addBtn.setPreferredSize(new Dimension(10, 40));
        addBtn.addActionListener(e -> addJobs());
        g.gridy=4; g.fill=GridBagConstraints.HORIZONTAL; g.insets=new Insets(10,5,2,5);
        opt.add(addBtn, g);

        top.add(opt);
        p.add(top, BorderLayout.NORTH);

        // キュー
        JPanel center = new JPanel(new BorderLayout(0,6));
        JPanel qHead = new JPanel(new BorderLayout()); qHead.setBorder(new EmptyBorder(8,2,0,2));
        JLabel qTitle = new JLabel("ダウンロードキュー"); qTitle.setFont(F_H2);
        qHead.add(qTitle, BorderLayout.WEST);
        JPanel qBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0)); qBtns.setOpaque(false);
        JButton bDone = ghostBtn("完了を消去"); bDone.addActionListener(e -> clearDone());
        JButton bRetry = ghostBtn("全て再試行"); bRetry.addActionListener(e -> retryAll());
        JButton bAll = ghostBtn("全消去"); bAll.addActionListener(e -> clearAll());
        qBtns.add(bDone); qBtns.add(bRetry); qBtns.add(bAll);
        qHead.add(qBtns, BorderLayout.EAST);
        center.add(qHead, BorderLayout.NORTH);

        queuePanel = new JPanel();
        queuePanel.setLayout(new BoxLayout(queuePanel, BoxLayout.Y_AXIS));
        JPanel holder = new JPanel(new BorderLayout()); holder.add(queuePanel, BorderLayout.NORTH);
        emptyLabel = new JLabel("URL を入力して「キューに追加」を押してください。", SwingConstants.CENTER);
        emptyLabel.setForeground(SUBTLE); emptyLabel.setBorder(new EmptyBorder(40,0,40,0));
        queuePanel.add(emptyLabel);
        JScrollPane qsp = new JScrollPane(holder);
        qsp.setBorder(null); qsp.getVerticalScrollBar().setUnitIncrement(16);
        center.add(qsp, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);

        // ログ
        JPanel logWrap = new JPanel(new BorderLayout(0,2));
        JPanel lHead = new JPanel(new BorderLayout()); lHead.setOpaque(false);
        lHead.add(small("ログ"), BorderLayout.WEST);
        JButton bLogClear = ghostBtn("クリア"); bLogClear.addActionListener(e -> logArea.setText(""));
        JPanel lc = new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0)); lc.setOpaque(false); lc.add(bLogClear);
        lHead.add(lc, BorderLayout.EAST);
        logWrap.add(lHead, BorderLayout.NORTH);
        logArea = new JTextArea(5, 10); logArea.setEditable(false); logArea.setFont(F_MONO);
        JScrollPane lsp = new JScrollPane(logArea);
        lsp.setPreferredSize(new Dimension(10, 110));
        logWrap.add(lsp, BorderLayout.CENTER);
        p.add(logWrap, BorderLayout.SOUTH);
        return p;
    }

    JCheckBox mkCheck(String text, boolean sel){
        JCheckBox c = new JCheckBox(text, sel); c.setOpaque(false); c.setFont(F_SMALL); return c;
    }

    // ── 履歴ページ ───────────────────────────────
    JComponent buildHistoryPage(){
        JPanel p = new JPanel(new BorderLayout(0,8));
        p.setBorder(new EmptyBorder(16,20,16,20));
        JLabel title = new JLabel("ダウンロード履歴"); title.setFont(F_TITLE);
        JPanel bar = new JPanel(new BorderLayout(8,0));
        tfHistSearch = new JTextField();
        tfHistSearch.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "タイトル / URL で検索");
        tfHistSearch.getDocument().addDocumentListener(new SimpleDoc(this::renderHistory));
        bar.add(tfHistSearch, BorderLayout.CENTER);
        JButton clr = ghostBtn("履歴をクリア"); clr.addActionListener(e -> clearHistory());
        bar.add(clr, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout(0,8));
        north.add(title, BorderLayout.NORTH); north.add(bar, BorderLayout.SOUTH);
        p.add(north, BorderLayout.NORTH);

        histPanel = new JPanel();
        histPanel.setLayout(new BoxLayout(histPanel, BoxLayout.Y_AXIS));
        JPanel holder = new JPanel(new BorderLayout()); holder.add(histPanel, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder); sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }
    void renderHistory(){
        if (histPanel == null) return;
        histPanel.removeAll();
        String q = tfHistSearch.getText() == null ? "" : tfHistSearch.getText().toLowerCase();
        int shown = 0;
        for (Hist h : cfg.history) {
            String t = (h.title != null && !h.title.isEmpty()) ? h.title : (h.url == null ? "" : h.url);
            String url = h.url == null ? "" : h.url;
            if (!q.isEmpty() && !t.toLowerCase().contains(q) && !url.toLowerCase().contains(q)) continue;
            shown++;
            JPanel row = card();
            row.setLayout(new BorderLayout(8,0));
            row.setBorder(new EmptyBorder(8,12,8,12));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
            JPanel txt = new JPanel(); txt.setOpaque(false);
            txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
            JLabel l1 = new JLabel(t); l1.setFont(F_BOLD); l1.setAlignmentX(LEFT_ALIGNMENT);
            JLabel l2 = new JLabel((h.dt==null?"":h.dt)+"    "+(h.fmt==null?"":h.fmt)+"    "+(h.result==null?"":h.result));
            l2.setFont(F_SMALL); l2.setForeground(SUBTLE); l2.setAlignmentX(LEFT_ALIGNMENT);
            txt.add(l1); txt.add(l2);
            row.add(txt, BorderLayout.CENTER);
            JButton reuse = ghostBtn("再利用");
            reuse.addActionListener(e -> { urlArea.append(url + "\n"); tabs.setSelectedIndex(0); });
            row.add(reuse, BorderLayout.EAST);
            histPanel.add(row); histPanel.add(Box.createVerticalStrut(6));
        }
        if (shown == 0) {
            JLabel none = new JLabel("履歴はありません。", SwingConstants.CENTER);
            none.setForeground(SUBTLE); none.setBorder(new EmptyBorder(40,0,40,0)); none.setAlignmentX(LEFT_ALIGNMENT);
            histPanel.add(none);
        }
        histPanel.revalidate(); histPanel.repaint();
    }

    // ── 設定ページ ───────────────────────────────
    JComponent buildSettingsPage(){
        JPanel root = new JPanel(new BorderLayout());
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(16,20,16,20));

        JLabel title = new JLabel("設定"); title.setFont(F_TITLE); title.setAlignmentX(LEFT_ALIGNMENT);
        p.add(title); p.add(Box.createVerticalStrut(10));

        // 一般
        JPanel gGen = group(p, "一般");
        cbParallel = combo(new String[]{"1","2","3","4","5"}, String.valueOf(cfg.maxParallel));
        gGen.add(formRow("同時ダウンロード数", cbParallel));
        ckNotify = check("ダウンロード完了時に通知を表示（システムトレイ）", cfg.notify); gGen.add(ckNotify);
        ckOpenAfter = check("完了後に保存フォルダを開く", cfg.openAfter); gGen.add(ckOpenAfter);
        ckConfirmExit = check("ダウンロード中に終了するとき確認する", cfg.confirmExit); gGen.add(ckConfirmExit);

        // ダウンロード
        JPanel gDl = group(p, "ダウンロード");
        cbFilePreset = combo(FILENAME_PRESETS.keySet().toArray(new String[0]), cfg.filenamePreset);
        cbFilePreset.addActionListener(e -> { onPresetChange(); updatePreview(); });
        gDl.add(formRow("ファイル名", cbFilePreset));
        tfCustomName = new JTextField(cfg.filenameCustom);
        tfCustomName.getDocument().addDocumentListener(new SimpleDoc(this::updatePreview));
        gDl.add(formRow("カスタム書式", tfCustomName));
        lbPreview = new JLabel(); lbPreview.setFont(F_NORMAL); lbPreview.setForeground(ACCENT);
        lbPreview.setAlignmentX(LEFT_ALIGNMENT);
        gDl.add(indent(lbPreview));
        cbOverwrite = combo(new String[]{"スキップ","上書き"}, cfg.overwrite);
        gDl.add(formRow("既存ファイル", cbOverwrite));
        tfSubLang = new JTextField(cfg.subLang);
        gDl.add(formRow("字幕の言語（例: ja,en / all）", tfSubLang));

        // ネットワーク
        JPanel gNet = group(p, "ネットワーク");
        tfSpeed = new JTextField(cfg.speedLimit);
        gNet.add(formRow("速度制限（例: 2M, 500K / 空欄=無制限）", tfSpeed));
        tfProxy = new JTextField(cfg.proxy);
        gNet.add(formRow("プロキシ（例: socks5://127.0.0.1:1080）", tfProxy));
        cbCookieBrowser = combo(new String[]{"chrome","edge","firefox","brave","opera","vivaldi"}, cfg.cookieBrowser);
        gNet.add(formRow("Cookie取得元ブラウザ", cbCookieBrowser));

        // 詳細
        JPanel gAdv = group(p, "詳細");
        JPanel ffrow = new JPanel(new BorderLayout(6,0)); ffrow.setOpaque(false);
        ffrow.setAlignmentX(LEFT_ALIGNMENT); ffrow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        tfFfmpeg = new JTextField(cfg.ffmpegPath);
        ffrow.add(tfFfmpeg, BorderLayout.CENTER);
        JButton bf = ghostBtn("参照"); bf.addActionListener(e -> browseFfmpeg());
        ffrow.add(bf, BorderLayout.EAST);
        gAdv.add(small("ffmpeg パス")); gAdv.add(ffrow);
        lbFfmpegStatus = new JLabel(); lbFfmpegStatus.setFont(F_SMALL); lbFfmpegStatus.setAlignmentX(LEFT_ALIGNMENT);
        gAdv.add(lbFfmpegStatus);
        gAdv.add(Box.createVerticalStrut(6));
        tfExtra = new JTextField(cfg.extraArgs);
        gAdv.add(formRow("追加オプション（例: --write-info-json）", tfExtra));
        ckAutoUpdate = check("起動時に yt-dlp を自動更新する", cfg.autoUpdate); gAdv.add(ckAutoUpdate);
        JPanel urow = new JPanel(new BorderLayout(8,0)); urow.setOpaque(false);
        urow.setAlignmentX(LEFT_ALIGNMENT); urow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        lbUpdateStatus = new JLabel(" "); lbUpdateStatus.setFont(F_SMALL); lbUpdateStatus.setForeground(SUBTLE);
        urow.add(lbUpdateStatus, BorderLayout.CENTER);
        JButton bUp = ghostBtn("yt-dlp を今すぐ更新"); bUp.addActionListener(e -> updateYtDlp());
        urow.add(bUp, BorderLayout.EAST);
        gAdv.add(urow);

        JButton save = primaryBtn("設定を保存");
        save.setPreferredSize(new Dimension(10,40)); save.setAlignmentX(LEFT_ALIGNMENT);
        save.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        save.addActionListener(e -> saveSettings());
        p.add(save);

        JScrollPane sp = new JScrollPane(p); sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        root.add(sp, BorderLayout.CENTER);
        return root;
    }

    JComboBox<String> combo(String[] items, String sel){
        JComboBox<String> c = new JComboBox<>(items); c.setSelectedItem(sel);
        c.setMaximumRowCount(8); return c;
    }
    JCheckBox check(String text, boolean sel){
        JCheckBox c = new JCheckBox(text, sel); c.setOpaque(false); c.setAlignmentX(LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); return c;
    }
    JPanel group(JPanel parent, String title){
        JPanel c = card();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBorder(new EmptyBorder(12,14,12,14));
        c.setAlignmentX(LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2000));
        JLabel t = new JLabel(title); t.setFont(F_H2); t.setForeground(ACCENT); t.setAlignmentX(LEFT_ALIGNMENT);
        c.add(t); c.add(Box.createVerticalStrut(8));
        parent.add(c); parent.add(Box.createVerticalStrut(12));
        return c;
    }
    JPanel formRow(String label, JComponent field){
        JPanel row = new JPanel(new BorderLayout(10,0)); row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        JLabel l = new JLabel(label); l.setForeground(SUBTLE); l.setPreferredSize(new Dimension(230, 28));
        row.add(l, BorderLayout.WEST); row.add(field, BorderLayout.CENTER);
        JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false);
        wrap.setAlignmentX(LEFT_ALIGNMENT); wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        wrap.setBorder(new EmptyBorder(3,0,3,0)); wrap.add(row);
        return wrap;
    }
    JComponent indent(JComponent c){
        JPanel w = new JPanel(new BorderLayout()); w.setOpaque(false);
        w.setBorder(new EmptyBorder(2,0,6,0)); w.setAlignmentX(LEFT_ALIGNMENT);
        w.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30)); w.add(c, BorderLayout.WEST);
        return w;
    }

    JComponent buildInfoPage(){
        JPanel root = new JPanel(new BorderLayout());
        JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(20,22,20,22));
        JLabel t = new JLabel(APP_TITLE); t.setFont(F_TITLE); t.setForeground(ACCENT); t.setAlignmentX(LEFT_ALIGNMENT);
        JLabel v = new JLabel("バージョン " + APP_VERSION); v.setFont(F_H2); v.setForeground(SUBTLE); v.setAlignmentX(LEFT_ALIGNMENT);
        p.add(t); p.add(v); p.add(Box.createVerticalStrut(12));
        String about =
            "yt-dlp を Windows で直感的に使うための GUI です（Java / Swing + FlatLaf）。\n\n"+
            "【使い方】\n  1. URL を貼り付け（複数行で一括追加可）\n  2. 種類（動画/音声）と画質・音質を選択\n  3. 保存先を確認して「キューに追加」\n\n"+
            "【形式】\n  動画 … MP4（H.264・高互換） / MKV（最高画質＋字幕）\n  音声 … MP3 / AAC / Opus / M4A（圧縮） / WAV / FLAC（ロスレス）\n\n"+
            "【必要な外部ツール】\n  yt-dlp.exe … jar と同じフォルダ\n  ffmpeg.exe … 設定で指定、または自動検出\n\n"+
            "【ヒント】\n  ・年齢制限/メンバー限定は「Cookie使用」を有効に\n  ・うまくいかない時はまず「設定 → yt-dlp を更新」\n  ・失敗ジョブはカードの「再試行」でやり直し\n\n"+
            "【動画ダウンロード時の音声】\n  常に最高品質の音声を選び、ffmpeg で無劣化に結合します（再エンコードなし）。";
        JTextArea ta = new JTextArea(about); ta.setEditable(false); ta.setOpaque(false); ta.setAlignmentX(LEFT_ALIGNMENT);
        p.add(ta);
        JScrollPane sp = new JScrollPane(p); sp.setBorder(null);
        root.add(sp, BorderLayout.CENTER);
        return root;
    }

    // ── 形式UI活性 ───────────────────────────────
    void onMediaChange(){
        boolean video = "動画".equals(cbMedia.getSelectedItem());
        cbVQ.setEnabled(video); cbVC.setEnabled(video);
        cbAF.setEnabled(!video);
        boolean lossless = AUDIO_LOSSLESS.contains((String) cbAF.getSelectedItem());
        cbAQ.setEnabled(!video && !lossless);
    }
    void onPresetChange(){
        if (cbFilePreset == null || tfCustomName == null) return;
        tfCustomName.setEnabled("カスタム".equals(cbFilePreset.getSelectedItem()));
    }
    String resolveTemplate(){
        String p = (String) cbFilePreset.getSelectedItem();
        if ("カスタム".equals(p)) {
            String c = tfCustomName.getText().trim();
            return c.isEmpty() ? "%(title)s.%(ext)s" : c;
        }
        return FILENAME_PRESETS.getOrDefault(p, "%(title)s.%(ext)s");
    }
    void updatePreview(){
        if (lbPreview == null) return;
        String tmpl = resolveTemplate();
        Map<String,String> s = new HashMap<>();
        s.put("title","動画タイトル例"); s.put("ext","mp4"); s.put("uploader","チャンネル名");
        s.put("upload_date","20240101"); s.put("id","dQw4w9WgXcQ");
        s.put("playlist_index","01"); s.put("playlist","プレイリスト名");
        Matcher m = Pattern.compile("%\\((\\w+)\\)s").matcher(tmpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(s.getOrDefault(m.group(1), m.group(0))));
        m.appendTail(sb);
        lbPreview.setText("例)  " + sb);
    }

    // ── URL/フォルダ ─────────────────────────────
    void pasteUrl(){
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String s = ((String) t.getTransferData(DataFlavor.stringFlavor)).trim();
                if (!s.isEmpty()) urlArea.append(s + "\n");
            }
        } catch (Exception ignored) {}
    }
    void startClip(){ lastClip=""; if (clipTimer==null) clipTimer=new javax.swing.Timer(1000, e -> checkClip()); clipTimer.start(); }
    void stopClip(){ if (clipTimer!=null) clipTimer.stop(); }
    void checkClip(){
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String s = ((String) t.getTransferData(DataFlavor.stringFlavor)).trim();
                if (!s.equals(lastClip) && s.matches("(?s)https?://.*")) {
                    lastClip = s;
                    if (!urlArea.getText().contains(s)) { urlArea.append(s + "\n"); log("[クリップボード] " + s + "\n"); }
                }
            }
        } catch (Exception ignored) {}
    }
    void browseDir(){
        JFileChooser fc = new JFileChooser(tfOutdir.getText());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) tfOutdir.setText(fc.getSelectedFile().getPath());
    }
    void openOutdir(){ File d = new File(tfOutdir.getText()); if (d.isDirectory()) try { Desktop.getDesktop().open(d); } catch (Exception ignored) {} }
    void browseFfmpeg(){
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("ffmpeg.exe が含まれるフォルダを選択");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) { tfFfmpeg.setText(fc.getSelectedFile().getPath()); updateFfmpegStatus(); }
    }

    // ── ジョブ追加・操作 ─────────────────────────
    void applyOptions(Job j){
        j.playlist = ckPlaylist.isSelected(); j.thumbnail = ckThumb.isSelected();
        j.subs = ckSubs.isSelected(); j.metadata = ckMeta.isSelected();
        j.chapters = ckChapters.isSelected(); j.cookies = ckCookies.isSelected();
        j.filenameTmpl = resolveTemplate();
    }
    void addJobs(){
        String raw = urlArea.getText().trim();
        if (raw.isEmpty()) { warn("URL未入力", "URL を1行に1つ入力してください。"); return; }
        String outdir = tfOutdir.getText().trim();
        if (outdir.isEmpty()) { warn("保存先未設定", "保存先フォルダを選択してください。"); return; }
        File od = new File(outdir);
        if (!od.isDirectory() && !od.mkdirs()) { warn("保存先エラー", "保存先を作成できません:\n"+outdir); return; }
        int added = 0;
        for (String line : raw.split("\\r?\\n")) {
            String url = line.trim(); if (url.isEmpty()) continue;
            Job j = new Job(url, buildFormat((String)cbMedia.getSelectedItem(), (String)cbVQ.getSelectedItem(),
                    (String)cbVC.getSelectedItem(), (String)cbAF.getSelectedItem(), (String)cbAQ.getSelectedItem()), outdir);
            applyOptions(j);
            jobs.add(j); addCard(j); submit(j); added++;
        }
        urlArea.setText("");
        log("[キュー] " + added + "件を追加しました → ダウンロード開始\n");
    }
    void addCard(Job j){
        if (emptyLabel.getParent() != null) queuePanel.remove(emptyLabel);
        JobCard c = new JobCard(j); j.card = c; cards.put(j.id, c);
        queuePanel.add(c); queuePanel.add(Box.createVerticalStrut(8));
        queuePanel.revalidate(); queuePanel.repaint();
    }
    void submit(Job j){ j.status = ST_WAITING; refresh(j); j.future = pool.submit(() -> runJob(j)); }
    void stopJob(Job j){
        Process p = j.process; if (p != null) p.destroy();
        if (j.future != null) j.future.cancel(false);
        j.status = ST_STOPPED; refresh(j);
    }
    void retryJob(Job j){ j.progress=0; j.speed=""; j.eta=""; j.error=""; j.log.clear(); submit(j); }
    void removeJob(Job j){
        if (ST_RUNNING.equals(j.status)) return;
        if (j.future != null) j.future.cancel(false);
        jobs.remove(j);
        JobCard c = cards.remove(j.id);
        if (c != null) {
            int idx = -1; Component[] comps = queuePanel.getComponents();
            for (int i=0;i<comps.length;i++) if (comps[i]==c){ idx=i; break; }
            queuePanel.remove(c);
            if (idx>=0 && idx<queuePanel.getComponentCount()) queuePanel.remove(idx);
        }
        if (jobs.isEmpty()) queuePanel.add(emptyLabel);
        queuePanel.revalidate(); queuePanel.repaint();
    }
    void retryAll(){ for (Job j : new ArrayList<>(jobs)) if (ST_ERROR.equals(j.status)||ST_STOPPED.equals(j.status)) retryJob(j); }
    void clearDone(){ for (Job j : new ArrayList<>(jobs)) if (ST_DONE.equals(j.status)) removeJob(j); }
    void clearAll(){
        for (Job j : new ArrayList<>(jobs)) { if (j.process!=null) j.process.destroy(); if (j.future!=null) j.future.cancel(false); }
        jobs.clear(); cards.clear();
        queuePanel.removeAll(); queuePanel.add(emptyLabel);
        queuePanel.revalidate(); queuePanel.repaint();
    }
    void refresh(Job j){ if (j.card != null) SwingUtilities.invokeLater(j.card::refresh); }

    // ── 実行 ─────────────────────────────────────
    void runJob(Job j){
        if (ST_STOPPED.equals(j.status)) return;
        Semaphore s = sem;
        try { s.acquire(); } catch (InterruptedException e) { return; }
        try {
            if (ST_STOPPED.equals(j.status)) return;
            j.status = ST_RUNNING; j.progress = 0; refresh(j);
            log("[#" + j.id + "] 開始: " + j.url + "\n");
            ProcessBuilder pb = new ProcessBuilder(j.buildCmd(cfg));
            pb.redirectErrorStream(true);
            Process p = pb.start(); j.process = p;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    j.log.add(line);
                    log("[#" + j.id + "] " + line + "\n");
                    Matcher md = P_DEST.matcher(line);
                    boolean hit = md.find();
                    if (!hit) { md = P_ALREADY.matcher(line); hit = md.find(); }
                    if (hit) {
                        try {
                            String fp = md.group(1).trim(); j.filepath = fp;
                            String name = new File(fp).getName().replaceFirst("\\.[^.]+$", "").replaceFirst("\\.f\\d+$", "");
                            if (!name.isEmpty()) j.title = name;
                            refresh(j);
                        } catch (Exception ignored) {}
                    }
                    Matcher mp = P_PROGRESS.matcher(line);
                    if (mp.find()) {
                        try { j.progress = Double.parseDouble(mp.group(1)); j.speed = mp.group(2); j.eta = mp.group(3); refresh(j); } catch (Exception ignored) {}
                    } else if (line.contains("[download] 100%")) { j.progress = 100; refresh(j); }
                }
            }
            int rc = p.waitFor();
            if (rc == 0) {
                j.status = ST_DONE; j.progress = 100;
                log("[#" + j.id + "] 完了\n");
                addHistory(j); notifyDone(j);
                if (cfg.openAfter) SwingUtilities.invokeLater(() -> openLocation(j));
            } else if (!ST_STOPPED.equals(j.status)) {
                j.status = ST_ERROR; j.error = analyzeError(j.log);
                log("[#" + j.id + "] エラー（コード" + rc + "）: " + j.error + "\n");
            }
        } catch (Exception e) {
            if (!ST_STOPPED.equals(j.status)) {
                j.status = ST_ERROR;
                String msg = String.valueOf(e.getMessage());
                j.error = (msg != null && msg.contains("CreateProcess")) ? "yt-dlp.exe が見つかりません（jarと同じフォルダに配置してください）" : msg;
                log("[#" + j.id + "] エラー: " + j.error + "\n");
            }
        } finally {
            j.process = null; refresh(j); s.release();
        }
    }
    void openLocation(Job j){
        try {
            File f = new File(j.filepath.isEmpty()? j.outdir : j.filepath);
            if (f.isFile()) new ProcessBuilder("explorer.exe","/select,", f.getPath()).start();
            else if (new File(j.outdir).isDirectory()) Desktop.getDesktop().open(new File(j.outdir));
        } catch (Exception ignored) {}
    }

    // ── 履歴 ─────────────────────────────────────
    void addHistory(Job j){
        Hist h = new Hist();
        h.dt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        h.url = j.url; h.title = j.title; h.fmt = j.label; h.result = j.status;
        cfg.history.add(0, h);
        if (cfg.history.size() > 200) cfg.history = new ArrayList<>(cfg.history.subList(0, 200));
        cfg.save();
        SwingUtilities.invokeLater(() -> { if (histPanel != null) renderHistory(); });
    }
    void clearHistory(){ if (confirm("ダウンロード履歴をすべて削除しますか？")) { cfg.history.clear(); cfg.save(); renderHistory(); } }

    void log(String t){ SwingUtilities.invokeLater(() -> { logArea.append(t); logArea.setCaretPosition(logArea.getDocument().getLength()); }); }

    // ── 設定保存 ─────────────────────────────────
    void updateFfmpegStatus(){
        Config tmp = new Config(); tmp.ffmpegPath = tfFfmpeg.getText().trim();
        String det = ffmpegPath(tmp);
        if (lbFfmpegStatus == null) return;
        if (!det.isEmpty()) { lbFfmpegStatus.setText("検出: " + det); lbFfmpegStatus.setForeground(SUCCESS); }
        else { lbFfmpegStatus.setText("⚠ ffmpeg が見つかりません（MP4/MKV結合・音声変換に必要）"); lbFfmpegStatus.setForeground(DANGER); }
    }
    void saveSettings(){
        String ff = tfFfmpeg.getText().trim();
        if (!ff.isEmpty()) {
            File p = new File(ff);
            boolean ok = (p.isDirectory() && new File(p,"ffmpeg.exe").exists()) || (p.isFile() && p.getName().equalsIgnoreCase("ffmpeg.exe"));
            if (!ok) { warn("ffmpegパスが無効","指定パスに ffmpeg.exe が見つかりません:\n"+ff+"\n\n空欄で自動検出になります。"); return; }
        }
        collectCfg(); cfg.save(); updateFfmpegStatus();
        sem = new Semaphore(Math.max(1, cfg.maxParallel));
        JOptionPane.showMessageDialog(this, "設定を保存しました。", "保存完了", JOptionPane.INFORMATION_MESSAGE);
    }
    void collectCfg(){
        cfg.outdir = tfOutdir.getText();
        cfg.media = (String) cbMedia.getSelectedItem();
        cfg.videoQuality = (String) cbVQ.getSelectedItem();
        cfg.videoContainer = (String) cbVC.getSelectedItem();
        cfg.audioFormat = (String) cbAF.getSelectedItem();
        cfg.audioQuality = (String) cbAQ.getSelectedItem();
        cfg.playlist = ckPlaylist.isSelected(); cfg.thumbnail = ckThumb.isSelected();
        cfg.subs = ckSubs.isSelected(); cfg.metadata = ckMeta.isSelected();
        cfg.chapters = ckChapters.isSelected(); cfg.cookies = ckCookies.isSelected();
        cfg.clipWatch = swClip.isSelected();
        if (cbParallel != null) cfg.maxParallel = Integer.parseInt((String) cbParallel.getSelectedItem());
        if (cbFilePreset != null) cfg.filenamePreset = (String) cbFilePreset.getSelectedItem();
        if (tfCustomName != null) cfg.filenameCustom = tfCustomName.getText();
        if (cbOverwrite != null) cfg.overwrite = (String) cbOverwrite.getSelectedItem();
        if (tfSubLang != null) cfg.subLang = tfSubLang.getText();
        if (tfSpeed != null) cfg.speedLimit = tfSpeed.getText();
        if (tfProxy != null) cfg.proxy = tfProxy.getText();
        if (cbCookieBrowser != null) cfg.cookieBrowser = (String) cbCookieBrowser.getSelectedItem();
        if (tfFfmpeg != null) cfg.ffmpegPath = tfFfmpeg.getText().trim();
        if (tfExtra != null) cfg.extraArgs = tfExtra.getText();
        if (ckNotify != null) cfg.notify = ckNotify.isSelected();
        if (ckOpenAfter != null) cfg.openAfter = ckOpenAfter.isSelected();
        if (ckConfirmExit != null) cfg.confirmExit = ckConfirmExit.isSelected();
        if (ckAutoUpdate != null) cfg.autoUpdate = ckAutoUpdate.isSelected();
    }

    // ── テーマ ───────────────────────────────────
    static void initLaf(String theme){
        String eff = theme.equals("system") ? (detectWindowsDark() ? "dark":"light") : theme;
        UIManager.put("defaultFont", new FontUIResource("Meiryo", Font.PLAIN, 13));
        UIManager.put("Component.accentColor", ACCENT);
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", false);
        UIManager.put("TabbedPane.showTabSeparators", false);
        try {
            if (eff.equals("light")) UIManager.setLookAndFeel(new FlatLightLaf());
            else UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception ignored) {}
    }
    void applyTheme(String theme){
        cfg.theme = theme; initLaf(theme); FlatLaf.updateUI(); cfg.save();
    }
    static boolean detectWindowsDark(){
        try {
            Process p = new ProcessBuilder("reg","query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v","AppsUseLightTheme").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, out=""; while ((line=r.readLine())!=null) out += line + "\n";
            p.waitFor();
            Matcher m = Pattern.compile("0x([0-9a-fA-F]+)").matcher(out);
            if (m.find()) return Integer.parseInt(m.group(1),16) == 0;
        } catch (Exception ignored) {}
        return true;
    }

    // ── yt-dlp バージョン/更新 ───────────────────
    void fetchVersion(){
        new Thread(() -> {
            String v = runCapture(Arrays.asList(ytDlpPath(), "--version"));
            String vv = v.isEmpty() ? "未検出" : v.trim().split("\\r?\\n")[0];
            SwingUtilities.invokeLater(() -> lbVersion.setText("yt-dlp: " + vv));
        }).start();
    }
    void updateYtDlp(){
        if (lbUpdateStatus != null) { lbUpdateStatus.setText("更新中…"); lbUpdateStatus.setForeground(INFO); }
        new Thread(() -> {
            String out = runCapture(Arrays.asList(ytDlpPath(), "-U"));
            String msg = out.trim(); if (msg.length() > 400) msg = msg.substring(msg.length()-400);
            String fmsg = msg.isEmpty() ? "完了" : msg;
            SwingUtilities.invokeLater(() -> { if (lbUpdateStatus != null){ lbUpdateStatus.setText(fmsg); lbUpdateStatus.setForeground(SUBTLE);} fetchVersion(); });
        }).start();
    }
    String runCapture(List<String> cmd){
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF8))) {
                String l; while ((l = r.readLine()) != null) sb.append(l).append("\n");
            }
            p.waitFor(); return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ── 通知 ─────────────────────────────────────
    void notifyDone(Job j){
        if (!cfg.notify || !SystemTray.isSupported()) return;
        try {
            if (trayIcon == null) {
                Image img = APP_ICONS.isEmpty() ? null : APP_ICONS.get(0);
                if (img == null) {
                    BufferedImage bi = new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
                    Graphics2D gg = bi.createGraphics();
                    gg.setColor(ACCENT); gg.fillRoundRect(0,0,16,16,6,6); gg.dispose();
                    img = bi;
                }
                trayIcon = new TrayIcon(img, "yt-dlp Studio"); trayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(trayIcon);
            }
            trayIcon.displayMessage("ダウンロード完了", (j.title.isEmpty()? j.url : j.title) + "\n" + j.label, TrayIcon.MessageType.INFO);
        } catch (Exception ignored) {}
    }

    // ── 終了 ─────────────────────────────────────
    void onClose(){
        long running = jobs.stream().filter(j -> ST_RUNNING.equals(j.status)).count();
        if (running > 0 && cfg.confirmExit && !confirm(running + "件のダウンロードが進行中です。終了しますか？")) return;
        for (Job j : jobs) if (j.process != null) j.process.destroy();
        try { collectCfg(); cfg.save(); } catch (Exception ignored) {}
        stopClip(); pool.shutdownNow();
        if (trayIcon != null) try { SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) {}
        dispose(); System.exit(0);
    }

    void warn(String title, String msg){ JOptionPane.showMessageDialog(this, msg, title, JOptionPane.WARNING_MESSAGE); }
    boolean confirm(String msg){ return JOptionPane.showConfirmDialog(this, msg, "確認", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION; }

    // ── ジョブカード ─────────────────────────────
    class JobCard extends JPanel {
        final Job job;
        JLabel dot, titleLbl, chip, statLbl;
        JProgressBar bar;
        JButton btnAction, btnFolder, btnRemove;
        JobCard(Job j){
            this.job = j;
            putClientProperty(FlatClientProperties.STYLE, "arc:14; border:1,1,1,1,$Component.borderColor");
            setLayout(new GridBagLayout());
            setBorder(new EmptyBorder(8,12,8,12));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 98));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(2,4,2,4);

            dot = new JLabel("●"); dot.setFont(FB(14));
            g.gridx=0; g.gridy=0; g.gridheight=3; g.anchor=GridBagConstraints.NORTH; add(dot, g);
            g.gridheight=1;

            titleLbl = new JLabel(j.displayName()); titleLbl.setFont(F_BOLD);
            g.gridx=1; g.gridy=0; g.weightx=1; g.fill=GridBagConstraints.HORIZONTAL; g.anchor=GridBagConstraints.WEST; add(titleLbl, g);
            g.weightx=0; g.fill=GridBagConstraints.NONE;

            chip = new JLabel(" " + j.label + " "); chip.setFont(F_SMALL); chip.setForeground(SUBTLE);
            chip.putClientProperty(FlatClientProperties.STYLE, "border:2,7,2,7,$Component.borderColor; arc:8");
            g.gridx=2; g.gridy=0; add(chip, g);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0)); btns.setOpaque(false);
            btnAction = mkCardBtn("停止"); btnAction.addActionListener(e -> onAction());
            btnFolder = mkCardBtn("開く"); btnFolder.addActionListener(e -> openLocation(job));
            btnRemove = mkCardBtn("削除"); btnRemove.addActionListener(e -> removeJob(job));
            btns.add(btnAction); btns.add(btnFolder); btns.add(btnRemove);
            g.gridx=3; g.gridy=0; g.gridheight=3; add(btns, g); g.gridheight=1;

            bar = new JProgressBar(0,100); bar.setValue(0); bar.setStringPainted(false);
            bar.setPreferredSize(new Dimension(10, 8));
            g.gridx=1; g.gridy=1; g.gridwidth=2; g.fill=GridBagConstraints.HORIZONTAL; g.weightx=1; add(bar, g);
            g.gridwidth=1; g.weightx=0;

            statLbl = new JLabel("待機中…"); statLbl.setFont(F_SMALL); statLbl.setForeground(SUBTLE);
            g.gridx=1; g.gridy=2; g.gridwidth=2; g.fill=GridBagConstraints.HORIZONTAL; add(statLbl, g);
            refresh();
        }
        JButton mkCardBtn(String t){
            JButton b = new JButton(t); b.setFont(F_SMALL); b.setFocusPainted(false);
            b.putClientProperty(FlatClientProperties.STYLE, "arc:8; focusWidth:0");
            return b;
        }
        Color statusColor(){
            switch (job.status) {
                case ST_RUNNING: return INFO; case ST_DONE: return SUCCESS;
                case ST_ERROR: return DANGER; case ST_STOPPED: return WARNING; default: return SUBTLE;
            }
        }
        void refresh(){
            Color c = statusColor();
            dot.setForeground(c);
            titleLbl.setText(job.displayName());
            chip.setText(" " + job.label + " ");
            bar.setValue((int)Math.round(job.progress));
            bar.setForeground(c);
            if (ST_RUNNING.equals(job.status)) {
                StringBuilder s = new StringBuilder(String.format("%.0f%%", job.progress));
                if (!job.speed.isEmpty()) s.append("    ").append(job.speed);
                if (!job.eta.isEmpty() && !job.eta.equals("Unknown")) s.append("    残り ").append(job.eta);
                statLbl.setText(s.toString()); statLbl.setForeground(c);
                btnAction.setText("停止"); btnAction.setEnabled(true);
            } else if (ST_DONE.equals(job.status)) {
                statLbl.setText("完了"); statLbl.setForeground(c);
                btnAction.setText("完了"); btnAction.setEnabled(false);
            } else if (ST_ERROR.equals(job.status)) {
                statLbl.setText("エラー: " + (job.error.isEmpty()?"":job.error)); statLbl.setForeground(c);
                btnAction.setText("再試行"); btnAction.setEnabled(true);
            } else if (ST_STOPPED.equals(job.status)) {
                statLbl.setText("停止しました"); statLbl.setForeground(c);
                btnAction.setText("再試行"); btnAction.setEnabled(true);
            } else {
                statLbl.setText("待機中…"); statLbl.setForeground(c);
                btnAction.setText("停止"); btnAction.setEnabled(true);
            }
        }
        void onAction(){
            if (ST_RUNNING.equals(job.status) || ST_WAITING.equals(job.status)) stopJob(job);
            else if (ST_ERROR.equals(job.status) || ST_STOPPED.equals(job.status)) retryJob(job);
        }
    }

    // ── DocumentListener 簡易版 ──────────────────
    static class SimpleDoc implements javax.swing.event.DocumentListener {
        final Runnable r; SimpleDoc(Runnable r){ this.r = r; }
        public void insertUpdate(javax.swing.event.DocumentEvent e){ r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e){ r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e){ r.run(); }
    }

    // ── セルフテスト ─────────────────────────────
    void runSelfTest(){
        StringBuilder sb = new StringBuilder();
        try {
            for (int i=0;i<tabs.getTabCount();i++){ tabs.setSelectedIndex(i); sb.append("tab ").append(tabs.getTitleAt(i)).append(" OK\n"); }
            Job j = new Job("https://example.com/watch?v=abc", buildFormat("動画","720p","MP4","MP3","最高"), ".");
            applyOptions(j);
            List<String> cmd = j.buildCmd(cfg);
            sb.append("encoding flag: ").append(cmd.contains("--encoding") && cmd.contains("utf-8") ? "OK":"NG").append("\n");
            sb.append("overwrite flag: ").append(cmd.contains("--no-overwrites")||cmd.contains("--force-overwrites") ? "OK":"NG").append("\n");
            sb.append("template: ").append(j.filenameTmpl).append("\n");
            sb.append("preview: ").append(lbPreview.getText()).append("\n");
            sb.append("RESULT OK\n");
        } catch (Exception e) {
            sb.append("RESULT FAIL: ").append(e).append("\n");
            for (StackTraceElement st : e.getStackTrace()) sb.append("  ").append(st).append("\n");
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(BASE_DIR,"_selftest_result.txt")), UTF8)) { w.write(sb.toString()); } catch (Exception ignored) {}
        dispose(); System.exit(0);
    }

    // ── スクリーンショット（オフスクリーン描画）─
    void runShot(){
        try {
            addNotify();
            setSize(1080, 760);
            validate();
            Container cp = getContentPane();
            int[] idx = {0, 2};
            String[] fn = {"shot_download.png", "shot_settings.png"};
            for (int k=0;k<idx.length;k++){
                tabs.setSelectedIndex(idx[k]);
                validate();
                cp.doLayout();
                for (Component c : cp.getComponents()) c.doLayout();
                Dimension d = cp.getSize();
                BufferedImage img = new BufferedImage(Math.max(1,d.width), Math.max(1,d.height), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                cp.printAll(g);
                g.dispose();
                javax.imageio.ImageIO.write(img, "png", new File(BASE_DIR, fn[k]));
            }
        } catch (Exception e) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(BASE_DIR,"_shot_err.txt")), UTF8)) { w.write(String.valueOf(e)); } catch (Exception ignored) {}
        }
        System.exit(0);
    }

    // ── main ─────────────────────────────────────
    public static void main(String[] args){
        Config cfg = Config.load();
        initLaf(cfg.theme);
        boolean selftest = args.length > 0 && args[0].equals("--selftest");
        boolean shot = args.length > 0 && args[0].equals("--shot");
        SwingUtilities.invokeLater(() -> {
            YtDlpStudio app = new YtDlpStudio(cfg);
            if (selftest) app.runSelfTest();
            else if (shot) app.runShot();
            else app.setVisible(true);
        });
    }
}
