# হ্যান্ডঅফ নোট (অন্য Claude/ডেভেলপারের জন্য)

প্রজেক্ট: নেটিভ অ্যান্ড্রয়েড নিউজ অ্যাপ (Kotlin + Compose + Media3). রেফারেন্স: `index.html` (ওয়েব ভার্সন)।
লক্ষ্য: অ্যাপের "🎬 ভিডিও পোস্ট করুন" এডিটর UI ও কাজ HTML-এর `#vcRoot` / `#vc_pub` / `#sv` এর সাথে মেলানো।

## এ পর্যন্ত করা (step32)
- `VideoEditorScreen.kt`: PublishDialog নতুন ডার্ক ডিজাইন; ফেইসবুক ব্যানারের ওপর/নিচের লেখা + ৫ রং; রেন্ডার শেষে `onRendered(uri)` কল।
- `VideoResultScreen.kt` (নতুন): HTML `#sv`-এর মতো "২ ভিডিও" পেইজ (ধাপ-বার, প্লেয়ার, টাইটেল, পাবলিশ/সেভ/ব্যাক/নতুন পোস্টে ফিরুন)।
- `ViewModels.kt` (EditorViewModel): `videoTitle`, `fbTop`, `fbBottom`, `fbColor` যোগ।
- `MainActivity.kt` (NewPostHost): `resultUri` দিয়ে এডিটর ↔ রেজাল্ট পেইজ।
- কম্পাইল/ডিভাইসে চালানো হয়নি; শুধু ব্র্যাকেট মেলানো হয়েছে।

## step33: নাম/পদবির "আবার দেখান"
- `Layers.kt`: `NameBarLayer.repeatSec`; `Layer.isActiveAt(ms, stopMs)` ও `repeatStopMs(clips)` (আউট্রো শুরুর ১৫ সে. আগে থামে)।
- `LayerPanel.kt` (সংখ্যার ঘর), `LayerRenderer.kt`/`RenderComposer.kt` (রেন্ডার), `DraftStore.kt` (সেভ "rep") আপডেট।
- HTML-এর সাথে একমাত্র পার্থক্য: প্রথমবার আসে লেয়ারের নিজের শুরু থেকে (HTML-এ ফিক্সড ১০ সেকেন্ড)।

## step34: এডজাস্টে কন্ট্রাস্ট
- `Layers.kt`: `AdjustLayer.contrast` (-১..১); `DraftStore.kt` সেভ/লোড ("contrast"); `LayerPanel.kt` স্লাইডার + রিসেট।
- রেন্ডার: `RenderComposer.kt`-এ `TimestampWrapper(Contrast(..))` (লেয়ারের সময়ে, ওভারলের নিচে)। সতর্কতা: Media3 1.4.1-এ `TimestampWrapper`/`Contrast` ক্লাসের নাম/সিগনেচার কম্পাইলে না মিললে এখানে ঠিক করুন।
- প্রিভিউ: `VideoEditorScreen.kt` FramePreview-তে ColorMatrix (আনুমানিক)।

## step35: অডিও ফেড ইন/আউট
- `Layers.kt`: `AudioLayer.fadeInMs/fadeOutMs`; `EditorDock.kt`: 🌅/🌇 বাটন প্রতি চাপে ০→১→২→০ সেকেন্ড; `DraftStore.kt` ("fi"/"fo"); `AudioMixer.kt` Sink-এ ফ্রেম ধরে গেইন-কার্ভ।
- প্রিভিউ প্লেয়ারে (PreviewPlayer) ফেড বাজে না, শুধু রেন্ডারে।

## step36: অডিও স্প্লিট, নয়েজ কমান, ক্লিপ রিসেট
- `TimelineModel.kt`: `splitAudioLayer(id)` (প্লেহেডে অডিও দুই ভাগ; প্রথম ভাগে ফেড-ইন, দ্বিতীয় ভাগে ফেড-আউট থাকে, দ্বিতীয়ের `sourceOffsetMs` এগোয়) ও `resetClipCuts(id)` (লাগোয়া একই-ভিডিওর টুকরো জুড়ে পুরো দৈর্ঘ্য, ট্রিম খোলা)।
- `Layers.kt`: `AudioLayer.denoise`; `DraftStore.kt` ("nc") সেভ/লোড।
- `AudioMixer.kt`: `NoiseReducer` (৯০Hz বাইকুয়াড হাই-পাস + ২০ মি.সে. নয়েজ গেট, স্ট্রিমিং) Sink-এ। HTML-এর সাথে পার্থক্য: HTML পুরো ফাইলের ১০% পার্সেন্টাইল দিয়ে ফ্লোর ধরে, এখানে চলমান সর্বনিম্ন এনার্জি দিয়ে ধরা হয় (আনুমানিক)।
- `EditorDock.kt`: তিনটি বাটন এখন আসল কাজ করে; নয়েজ বাটন টগল ("নয়েজ ফেরান")।
- প্রিভিউ প্লেয়ারে নয়েজ কমানো বাজে না, শুধু রেন্ডারে (ফেডের মতোই)। কম্পাইল/ডিভাইসে চালানো হয়নি, শুধু ব্র্যাকেট মেলানো।

## এখনো ডামি/বাকি
- FB ব্যানারের লেখা/রং রেন্ডারে বসে না (শুধু state)।
- 🚀 পাবলিশে আসল আপলোড (Facebook/YouTube/WordPress) নেই।
- ইন্ট্রো/আউট্রো: অ্যাপে ৪ স্টাইল (স্থির ছবি); HTML-এ আউট্রো ৬ স্টাইল, অ্যানিমেশন, সাউন্ড।
- সেটিংসের বিজ্ঞাপন/লোগো তালিকা থেকে বাছাই নেই; "ফুল স্ক্রিন ভিডিও" টিক নেই।
- স্টেজে টেনে লেয়ার সরানো/বড়-ছোট করা নেই (এখন শুধু স্লাইডার, LayerPanel.kt)।

## নিয়ম
- সব UI লেখা বাংলায়। HTML ফাইল দেখে মিলিয়ে কাজ করুন।
- বদলের পর brace/paren মিলিয়ে দেখুন; GitHub Actions (`.github/workflows/build.yml`) দিয়ে বিল্ড করে ত্রুটি ঠিক করুন।
