package com.digitalcrafting25.newsapp

/**
 * সেটিংস → 🔑 সংযোগ-এর সব গাইড। প্রতিটা সেটিংসে "📖 ... গাইড" লিংকে চাপলে আলাদা ফুল পেইজে খোলে।
 * (Meta/Google-এর মেনুর নাম মাঝে মাঝে বদলায়; এখানে যে নাম লেখা আছে সেটা না পেলে কাছাকাছি নামেরটা দেখুন।)
 */

private const val DEV = "https://developers.facebook.com"

internal val OVERVIEW_GUIDE = GDoc(
    title = "সব সংযোগ এক নজরে",
    intro = "এই অ্যাপ আপনার WordPress সাইটে খবর দেয়, Facebook পেইজে ছবি ও কমেন্ট করে, আর চাইলে একই ছবি Instagram ও Threads-এও পোস্ট করে। কোনটার জন্য কী লাগে নিচে দেখুন। প্রতিটার পূর্ণ ধাপ আলাদা গাইডে আছে, সেটিংসে সেই সংযোগের নিচের 📖 লিংকে চাপলে খুলবে।",
    needs = listOf(
        "ইন্টারনেট চালু আর একটা কম্পিউটার বা ফোনের ব্রাউজার (Meta-র সাইট কম্পিউটারে সবচেয়ে সহজ)।",
        "সব টোকেন ও পাসওয়ার্ড গোপন রাখুন। কারও সাথে শেয়ার করবেন না।",
    ),
    steps = listOf(
        GStep("WordPress (সাইটে খবর পোস্ট)", listOf("লাগবে: সাইটের URL, ইউজারনেম, Application Password।", "সময়: প্রায় ৫ মিনিট।", "গাইড: WordPress সংযোগের নিচের 📖 লিংক।")),
        GStep("Facebook পেইজ (ছবি ও কমেন্ট)", listOf("লাগবে: Page ID আর Page Access Token।", "সময়: প্রায় ২০ মিনিট (প্রথমবার)।", "গাইড: Facebook সংযোগের নিচের 📖 লিংক।"), note = "Instagram-এর জন্য আলাদা টোকেন লাগে না, একই Facebook টোকেনই চলে। শুধু টোকেন বানানোর সময় Instagram-এর পারমিশনও দিতে হয়।"),
        GStep("Instagram (Facebook-এর সাথে যুক্ত)", listOf("লাগবে: Business বা Creator Instagram অ্যাকাউন্ট, Facebook পেইজের সাথে যুক্ত, আর Instagram পারমিশনসহ Facebook টোকেন।", "অ্যাপে আলাদা কিছু বসাতে হয় না। সেভ করা Facebook টোকেন দিয়েই চলে।", "গাইড: Instagram অংশের নিচের 📖 লিংক।")),
        GStep("Threads", listOf("লাগবে: Threads Access Token (User ID ঐচ্ছিক)।", "সময়: প্রায় ১৫ মিনিট। টোকেনের মেয়াদ ৬০ দিন, তাই মাঝে মাঝে বাড়াতে হয়।", "গাইড: Threads অংশের নিচের 📖 লিংক।")),
        GStep("YouTube (ভিডিও পাবলিশ)", listOf("লাগবে: Google Cloud-এ Android OAuth client (পাসওয়ার্ড বা সিক্রেট বসাতে হয় না)।", "গাইড: YouTube অংশের নিচের 📖 লিংক।")),
    ),
    plain = listOf(
        "সুপারিশ করা ক্রম: ১) WordPress, ২) Facebook, ৩) Instagram যাচাই, ৪) Threads, ৫) YouTube।",
        "প্রতিটা সংযোগ সেভ করার পর সেটার 🔌 টেস্ট বাটন চাপুন। টেস্ট কিছু পোস্ট করে না, শুধু দেখে সংযোগ ঠিক আছে কিনা এবং ভুল থাকলে কোথায় তা বলে দেয়।",
        "Facebook-এ পোস্ট সফল হলে এবং পোস্টের আগের টিক বক্সে টিক থাকলে তবেই Instagram ও Threads-এ যায় (এক্সট্রা ১ টাকা, সফল হলে)। Instagram বা Threads ব্যর্থ হলে Facebook ও WordPress-এর পোস্ট নষ্ট হয় না, আর ব্যর্থ হলে ওই টাকা কাটে না।",
    ),
)

internal val WP_GUIDE = GDoc(
    title = "WordPress সংযোগ: ধাপে ধাপে",
    intro = "অ্যাপ আপনার লগইন পাসওয়ার্ড ব্যবহার করে না। WordPress-এর নিজস্ব Application Password দিয়ে সাইটে খবর পোস্ট করে। এটা যেকোনো সময় WordPress থেকে বাতিল করা যায়।",
    needs = listOf(
        "আপনার WordPress সাইটের অ্যাডমিন লগইন (ইউজারের রোল Author, Editor বা Administrator হতে হবে)।",
        "সাইটে HTTPS চালু থাকতে হবে (ঠিকানা https:// দিয়ে শুরু)। নইলে Application Passwords অপশন আসে না।",
        "WordPress-এর ভার্সন ৫.৬ বা নতুন।",
    ),
    steps = listOf(
        GStep(
            "WordPress ড্যাশবোর্ডে লগইন করুন",
            listOf("ব্রাউজারে আপনার সাইটের ঠিকানার শেষে /wp-admin লিখে খুলুন এবং ইউজারনেম ও পাসওয়ার্ড দিয়ে ঢুকুন।"),
            code = listOf("https://আপনারসাইট.com/wp-admin"),
        ),
        GStep(
            "নিজের প্রোফাইল পেইজ খুলুন",
            listOf("বাঁ দিকের মেনুতে Users (ব্যবহারকারী) চাপুন, তারপর Profile (প্রোফাইল) চাপুন।", "শুধু Profile মেনু থাকলে সেটাই চাপুন। এটা আপনার নিজের ইউজারের পেইজ।"),
        ),
        GStep(
            "Application Password বানান",
            listOf(
                "পেইজের একদম নিচে যান। Application Passwords নামে অংশ পাবেন।",
                "New Application Password Name ঘরে যেকোনো নাম লিখুন, যেমন News App।",
                "Add New Application Password বাটন চাপুন।",
            ),
            note = "Application Passwords অংশ না দেখালে নিচের “সমস্যা হলে” অংশটা পড়ুন।",
        ),
        GStep(
            "পাসওয়ার্ডটি সঙ্গে সঙ্গে কপি করুন",
            listOf(
                "একটা সবুজ বক্সে ২৪ অক্ষরের পাসওয়ার্ড দেখাবে (৪ অক্ষর করে ৬ ভাগে, মাঝে স্পেস)।",
                "Copy করে অ্যাপে বসানোর জায়গায় রাখুন। স্পেসসহ বসালেও চলবে।",
            ),
            note = "এই পাসওয়ার্ড দ্বিতীয়বার দেখাবে না। হারিয়ে ফেললে পুরোনোটা Revoke করে নতুন বানাতে হবে।",
        ),
        GStep(
            "ইউজারনেম দেখে নিন",
            listOf(
                "একই প্রোফাইল পেইজের একদম উপরে Username ঘরের লেখাটাই আপনার ইউজারনেম।",
                "Nickname বা Display name দিলে হবে না। লগইন পাসওয়ার্ডও এখানে দেওয়া যাবে না।",
            ),
        ),
        GStep(
            "অ্যাপে তথ্য বসান",
            listOf(
                "অ্যাপে সেটিংস খুলুন, তারপর “🔑 WordPress ও Facebook সংযোগ” কার্ড খুলুন।",
                "সাইটের URL: https:// দিয়ে লিখুন, শেষে / দেবেন না (যেমন https://mysite.com)।",
                "ইউজারনেম আর অ্যাপ্লিকেশন পাসওয়ার্ড ঘরে ধাপ ৪ ও ৫-এর মান বসান।",
            ),
        ),
        GStep(
            "সেভ ও টেস্ট করুন",
            listOf(
                "নিচের সবুজ “💾 সংযোগ সেভ করুন” বাটন চাপুন।",
                "WordPress ঘরের “🔌 টেস্ট করুন” চাপুন। সফল হলে ✅ দেখাবে।",
                "ভুল থাকলে লাল লেখায় কারণ বলে দেবে।",
            ),
        ),
    ),
    problems = listOf(
        "Application Passwords অংশই দেখাচ্ছে না" to "সাইটে HTTPS আছে কিনা দেখুন। Wordfence বা অন্য সিকিউরিটি প্লাগিন, বা “Disable Application Passwords” জাতীয় প্লাগিন এটা বন্ধ রাখতে পারে। সেই প্লাগিনের সেটিংসে এটা চালু করুন।",
        "টেস্টে 401 বা অনুমতি নেই বলছে" to "ইউজারনেম ঠিক আছে কিনা দেখুন (Username ঘর, Display name নয়)। নতুন Application Password বানিয়ে আবার বসান। ইউজারের রোল Author/Editor/Administrator কিনা দেখুন।",
        "টেস্টে 403 বা ব্লক হয়েছে বলছে" to "হোস্টিং ফায়ারওয়াল, Cloudflare বা সিকিউরিটি প্লাগিন /wp-json পথ আটকে দিয়েছে। সেখানে REST API অনুমতি দিন।",
        "পাসওয়ার্ড বসানোর পর কাজ করছে না" to "পাসওয়ার্ডের আগে-পরে ফাঁকা জায়গা কপি হয়ে গেছে কিনা দেখুন, আর সেভ করার পর টেস্ট করেছেন কিনা দেখুন।",
    ),
    done = "টেস্ট ✅ হলে WordPress সংযোগ শেষ। এবার Facebook গাইডে যান।",
)

internal val FB_GUIDE = GDoc(
    title = "Facebook পেইজ সংযোগ: ধাপে ধাপে",
    intro = "অ্যাপ Facebook পেইজে ছবি পোস্ট করে আর কমেন্টে লিংক দেয়। এজন্য দুটো জিনিস লাগে: পেইজের Page ID আর Page Access Token। এই একই টোকেনে Instagram-ও চলে, তাই আজই Instagram-এর পারমিশনগুলো সহ বানিয়ে নেওয়া সবচেয়ে ভালো।",
    needs = listOf(
        "যে Facebook অ্যাকাউন্ট ওই পেইজের অ্যাডমিন সেটা দিয়ে লগইন।",
        "কম্পিউটার সবচেয়ে সহজ। ফোনে করলে ব্রাউজারে “Desktop site” চালু করুন।",
        "Instagram-ও চালাতে চাইলে: Instagram Business/Creator অ্যাকাউন্ট পেইজের সাথে যুক্ত থাকা (Instagram গাইড দেখুন)।",
        "সময়: প্রথমবার প্রায় ২০ মিনিট।",
    ),
    steps = listOf(
        GStep(
            "Meta for Developers-এ ঢুকুন",
            listOf(
                "নিচের লিংকে গিয়ে Facebook অ্যাকাউন্ট দিয়ে লগইন করুন।",
                "প্রথমবার হলে Get Started চেপে ডেভেলপার অ্যাকাউন্ট খুলুন (ফোন নম্বর/ইমেইল যাচাই করতে বলতে পারে)।",
            ),
            links = listOf("developers.facebook.com খুলুন" to DEV),
        ),
        GStep(
            "অ্যাপ বানান (আগে বানানো থাকলে এই ধাপ বাদ)",
            listOf(
                "My Apps → Create App চাপুন।",
                "অ্যাপের নাম দিন (যেমন News Auto) আর আপনার ইমেইল দিন।",
                "Use cases পেইজে Manage everything on your Page বেছে নিন।",
                "Instagram-ও চালাতে চাইলে একই জায়গায় Manage messaging and content on Instagram-ও টিক দিন। একসাথে বাছতে না দিলে অ্যাপ তৈরির পর বাঁ মেনুর Use cases থেকে Add করুন।",
                "Business portfolio চাইলে “I don’t want to connect a business portfolio yet” বাছলেই চলবে। তারপর Create app চাপুন (পাসওয়ার্ড চাইতে পারে)।",
            ),
        ),
        GStep(
            "Instagram ব্যবহার করলে: Instagram use case-এর পারমিশন চালু করুন",
            listOf(
                "অ্যাপ ড্যাশবোর্ডে বাঁ মেনু থেকে Use cases খুলুন।",
                "Manage messaging and content on Instagram-এর পাশের Customize চাপুন।",
                "বাঁ মেনুতে API setup with Facebook login বেছে নিন। Add required content permissions বাটন থাকলে চাপুন।",
                "ড্যাশবোর্ডে “Unified login” লেখা থাকলে ঘাবড়াবেন না। পরের ধাপের টোকেন বানানোই মূল কাজ।",
            ),
        ),
        GStep(
            "Graph API Explorer খুলুন",
            listOf(
                "নিচের লিংকে চাপুন। ডান পাশের প্যানেলে Meta App-এর ড্রপডাউন থেকে আপনার অ্যাপটি বাছুন।",
                "User or Page ড্রপডাউনে User Token থাকা অবস্থায় রাখুন।",
            ),
            links = listOf("Graph API Explorer খুলুন" to "$DEV/tools/explorer/"),
        ),
        GStep(
            "পারমিশনগুলো যোগ করুন",
            listOf(
                "Permissions অংশে Add a Permission চেপে নিচের নামগুলো খুঁজে একটা একটা করে যোগ করুন।",
                "শুধু Facebook-এর জন্য প্রথম চারটা যথেষ্ট। Instagram-ও চালাতে হলে সবগুলো দিন।",
            ),
            code = listOf(
                "pages_show_list",
                "pages_read_engagement",
                "pages_manage_posts",
                "pages_manage_engagement",
                "instagram_basic",
                "instagram_content_publish",
                "instagram_manage_comments",
            ),
            note = "পেইজটি Business Manager/Portfolio-এর মাধ্যমে চালানো হলে Meta-র নিয়মে ads_management (বা ads_read) পারমিশনও লাগতে পারে।",
        ),
        GStep(
            "Generate Access Token চাপুন",
            listOf(
                "নীল Generate Access Token বাটন চাপুন। একটা পপআপ আসবে।",
                "পপআপে আপনার পেইজটি টিক দিন। Instagram অ্যাকাউন্টের তালিকা এলে সেটাও টিক দিন।",
                "Continue/Save চেপে অনুমতি দিন। এতে উপরের Access Token ঘরে একটা টোকেন বসবে।",
            ),
            note = "পেইজ টিক না দিলে পরের ধাপে me/accounts ফাঁকা আসবে।",
        ),
        GStep(
            "টোকেনের মেয়াদ বাড়ান (স্থায়ী করার প্রথম ধাপ)",
            listOf(
                "নিচের Access Token Debugger খুলুন এবং টোকেনটা ঘরে বসিয়ে Debug চাপুন।",
                "একদম নিচে Extend Access Token বাটন চাপুন। যে লম্বা টোকেন দেখাবে সেটা কপি করুন।",
            ),
            links = listOf("Access Token Debugger খুলুন" to "$DEV/tools/debug/accesstoken/"),
        ),
        GStep(
            "Page Access Token ও Page ID বের করুন",
            listOf(
                "আবার Graph API Explorer-এ ফিরে Access Token ঘরে ধাপ ৭-এর লম্বা টোকেনটা বসান।",
                "টোকেন ঘরের নিচে GET বাটনের পাশের ঘরে নিচের লেখাটা বসিয়ে Submit চাপুন।",
                "ফলাফলে আপনার পেইজের নামের নিচে id আর access_token পাবেন। এই access_token-ই Page Access Token, আর id-ই Page ID।",
                "Instagram যুক্ত থাকলে ওই একই ফলাফলে instagram_business_account লেখাও দেখাবে।",
            ),
            code = listOf("me/accounts?fields=name,id,access_token,instagram_business_account"),
            note = "লম্বা-মেয়াদি ইউজার টোকেন থেকে নেওয়া Page টোকেনের সাধারণত মেয়াদ শেষ হয় না। পাসওয়ার্ড বদলালে বা পেইজের অ্যাডমিন থেকে সরে গেলে বাতিল হয়।",
        ),
        GStep(
            "অ্যাপে বসিয়ে সেভ করুন",
            listOf(
                "অ্যাপের সেটিংস → “🔑 WordPress ও Facebook সংযোগ” → Facebook পেইজ অংশ।",
                "Page ID ঘরে শুধু সংখ্যাটা বসান। Page Access Token ঘরে access_token বসান (EAA... দিয়ে শুরু)।",
                "নিচের “💾 সংযোগ সেভ করুন” চাপুন, তারপর Facebook অংশের “🔌 টেস্ট করুন” চাপুন।",
                "টেস্টে পেইজের নাম আর Instagram যুক্ত থাকলে তার @নাম দেখাবে। কোনো পারমিশন বাদ থাকলে নামসহ বলে দেবে।",
            ),
        ),
        GStep(
            "অ্যাপ Live করুন (পোস্ট সবার কাছে দেখানোর জন্য)",
            listOf(
                "Meta ড্যাশবোর্ডে App settings → Basic খুলুন।",
                "Privacy Policy URL ঘরে আপনার সাইটের গোপনীয়তা নীতির পেইজের ঠিকানা দিন (যেকোনো পাবলিক পেইজ হলেই চলে) এবং Save changes চাপুন।",
                "উপরের App mode সুইচটা Development থেকে Live করুন।",
            ),
            note = "Development মোডে করা পোস্ট শুধু অ্যাপের রোলধারীরা দেখতে পায়, সবাই নয়।",
        ),
    ),
    problems = listOf(
        "me/accounts ফাঁকা আসছে" to "টোকেন বানানোর সময় পপআপে পেইজ টিক দেননি। আবার Generate Access Token চেপে পেইজ টিক দিন। আর আপনার অ্যাকাউন্ট ওই পেইজের পূর্ণ অ্যাডমিন কিনা দেখুন।",
        "টেস্টে “Instagram যুক্ত নেই” লিখছে" to "Instagram অ্যাকাউন্ট Business/Creator কিনা এবং এই পেইজের সাথে যুক্ত কিনা দেখুন। Instagram গাইডের ধাপ ১ ও ২ দেখুন।",
        "টেস্টে “টোকেনে Instagram পারমিশন নেই” লিখছে" to "ধাপ ৫-এ instagram_basic, instagram_content_publish, instagram_manage_comments যোগ করে ধাপ ৬ থেকে ৯ আবার করুন এবং নতুন Page Access Token অ্যাপে সেভ করুন।",
        "Invalid OAuth access token / session has expired" to "টোকেন বাতিল হয়েছে (পাসওয়ার্ড বদল বা অ্যাডমিন পরিবর্তন)। ধাপ ৪ থেকে নতুন করে বানান।",
        "(#200) বা (#10) পারমিশন নেই" to "পারমিশন তালিকায় pages_manage_posts ও pages_manage_engagement আছে কিনা দেখুন, তারপর নতুন টোকেন বানান।",
        "পোস্ট হচ্ছে কিন্তু অন্যরা দেখছে না" to "অ্যাপ এখনো Development মোডে। ধাপ ১০ করে Live করুন।",
    ),
    done = "টেস্ট ✅ হলে Facebook সংযোগ শেষ। এবার Instagram যাচাই করুন, তারপর Threads।",
)

internal val IG_GUIDE = GDoc(
    title = "Instagram সংযোগ: ধাপে ধাপে",
    intro = "Instagram-এর জন্য অ্যাপে আলাদা কোনো ঘরে কিছু বসাতে হয় না। আপনার Instagram অ্যাকাউন্ট Facebook পেইজের সাথে যুক্ত থাকলে, সেভ করা Facebook Page Access Token দিয়েই অ্যাপ ছবি পোস্ট করে এবং কমেন্টে লিংক বসায়। আপনার কাজ হলো তিনটা জিনিস ঠিক আছে কিনা নিশ্চিত করা: ১) অ্যাকাউন্ট Business/Creator, ২) পেইজের সাথে যুক্ত, ৩) টোকেনে Instagram পারমিশন আছে।",
    needs = listOf(
        "একটা Instagram অ্যাকাউন্ট যেটা Professional (Business বা Creator) করা।",
        "একটা Facebook পেইজ (ব্যক্তিগত প্রোফাইল নয়) এবং Facebook সংযোগ (আগের গাইড) সম্পন্ন।",
    ),
    steps = listOf(
        GStep(
            "Instagram অ্যাকাউন্টকে Professional করুন",
            listOf(
                "Instagram অ্যাপে নিজের প্রোফাইলে যান, উপরের ডানের তিন দাগ মেনু (☰) চাপুন।",
                "Settings and privacy (সেটিংস ও প্রাইভেসি) খুলুন, তারপর Account type and tools (অ্যাকাউন্ট টাইপ ও টুলস) বা Professional account অংশে যান।",
                "Switch to professional account চাপুন। Business বা Creator যেকোনোটা বাছুন, একটা ক্যাটেগরি দিন।",
                "আগে থেকেই Business/Creator থাকলে এই ধাপ লাগবে না।",
            ),
            note = "মেনুর নাম Instagram-এর আপডেটে কিছুটা আলাদা হতে পারে। “Professional account” বা “Account type” খুঁজুন।",
        ),
        GStep(
            "Facebook পেইজের সাথে যুক্ত করুন (প্রথম উপায়: Instagram থেকে)",
            listOf(
                "Instagram-এ প্রোফাইল → Edit profile (প্রোফাইল সম্পাদনা) চাপুন।",
                "Public business information অংশে Page চাপুন, তারপর আপনার পেইজটি বেছে Connect করুন।",
                "Facebook লগইন চাইলে ওই পেইজের অ্যাডমিন অ্যাকাউন্টে লগইন করুন।",
            ),
        ),
        GStep(
            "অথবা Facebook পেইজ থেকে যুক্ত করুন (দ্বিতীয় উপায়)",
            listOf(
                "Facebook-এ আপনার পেইজে যান (পেইজ হিসেবে, ব্যক্তিগত প্রোফাইল হিসেবে নয়)।",
                "Settings → Linked accounts (সংযুক্ত অ্যাকাউন্ট) → Instagram চাপুন।",
                "Connect account চেপে Instagram-এর ইউজারনেম ও পাসওয়ার্ড দিন।",
            ),
        ),
        GStep(
            "যুক্ত হয়েছে কিনা দেখুন",
            listOf(
                "Facebook পেইজের Settings → Linked accounts-এ Instagram অ্যাকাউন্টের নাম দেখালে যুক্ত আছে।",
                "অথবা অ্যাপে Instagram অংশের “🔌 Instagram যাচাই করুন” চাপুন। যুক্ত থাকলে ✅ ও @ইউজারনেম দেখাবে, আর নিচের “Instagram সেটাপ করা হয়েছে” সুইচ নিজে থেকে চালু হয়ে যাবে।",
                "সুইচ চালু না হলে নিজে চালু করুন। এটা চালু না থাকলে পোস্টের আগে Instagram-এর টিক বক্স আসবে না।",
            ),
        ),
        GStep(
            "টোকেনে Instagram পারমিশন আছে কিনা নিশ্চিত করুন",
            listOf(
                "আগে বানানো Facebook টোকেনে এই তিনটা না থাকলে Facebook গাইডের ধাপ ৪ থেকে ৯ আবার করে নতুন Page Access Token বানান।",
                "বানানোর সময় পপআপে আপনার Instagram অ্যাকাউন্টও টিক দিন।",
                "নতুন টোকেন অ্যাপে Facebook অংশে বসিয়ে সেভ করুন।",
            ),
            code = listOf("instagram_basic", "instagram_content_publish", "instagram_manage_comments"),
        ),
        GStep(
            "ছবির নিয়ম জেনে রাখুন",
            listOf(
                "Instagram শুধু JPEG ছবি নেয়, আকার ৮ MB পর্যন্ত।",
                "ছবির চওড়া-উচ্চতার অনুপাত ৪:৫ (লম্বা) থেকে ১.৯১:১ (চওড়া) এর মধ্যে হতে হবে। অ্যাপের স্বাভাবিক ফোটোকার্ডগুলো এর মধ্যেই পড়ে।",
                "অ্যাপ Facebook-এ যাওয়া একই ছবির পাবলিক লিংক Instagram-কে দেয়, তাই আলাদা আপলোড লাগে না।",
            ),
        ),
        GStep(
            "পোস্ট করলে কী হবে",
            listOf(
                "পোস্ট করার আগে (নতুন পোস্ট, ফোটোকার্ড, বক্তব্য কার্ড) বাটনের উপরে “Instagram ও Threads-এও পোস্ট করুন” টিক বক্স থাকে। ডিফল্টে টিক দেওয়া। টিক তুলে দিলে শুধু Facebook ও WordPress-এ যায়।",
                "টিক থাকলে Facebook-এ পোস্ট সফল হওয়ার পর একই ছবি Instagram-এ যায়। নিউজ পোস্টে ক্যাপশন হয় খবরের শিরোনাম, ফোটোকার্ডে ক্যাপশন থাকে না (Facebook-এর মতো)।",
                "এক্সট্রা খরচ: Instagram বা Threads-এর যেকোনো একটায় (বা দুটোতেই) পোস্ট সফল হলে ব্যালেন্স থেকে একবার এক্সট্রা ১ টাকা কাটে। দুটোই ব্যর্থ হলে কাটে না। রিট্রাইয়ে সফল হলেও একই পোস্টে একবারের বেশি কাটে না। রেট অ্যাডমিনের “💰 রেট” মেনু থেকে বদলানো যায়।",
                "এরপর Instagram পোস্টের কমেন্টে “বিস্তারিত পড়ুন: লিংক” বসে।",
                "ছবি প্রসেস হতে ৩০ সেকেন্ড পর্যন্ত লাগতে পারে।",
                "Instagram বন্ধ রাখতে চাইলে Worker-এ IG_OFF=1 ভ্যারিয়েবল দিন।",
            ),
            note = "Instagram কমেন্টের লিংকে চাপ দেওয়া যায় না, শুধু কপি করা যায়। এটা Instagram-এর নিয়ম, অ্যাপের ত্রুটি নয়।",
        ),
    ),
    problems = listOf(
        "টেস্টে “Instagram যুক্ত নেই” দেখাচ্ছে" to "ধাপ ১-৩ ঠিকমতো হয়েছে কিনা দেখুন। অ্যাকাউন্ট Business/Creator না হলে যুক্ত হয় না। একই Facebook অ্যাকাউন্ট দিয়ে যুক্ত করেছেন কিনাও দেখুন।",
        "Permission/ (#10) / (#200) ত্রুটি" to "টোকেনে Instagram পারমিশন নেই। ধাপ ৫ অনুযায়ী নতুন টোকেন বানান।",
        "ছবি প্রসেস হয়নি বা মিডিয়া ঠিক নেই" to "ছবির অনুপাত ৪:৫ থেকে ১.৯১:১-এর বাইরে হলে এমন হয়। অতিরিক্ত চওড়া বা লম্বা কার্ড টেমপ্লেট ব্যবহার করলে এই সমস্যা হবে।",
        "Facebook-এ গেছে কিন্তু Instagram-এ যায়নি" to "পোস্টের ফলাফলে instagram_error লেখা থাকে, সেটা পড়ুন। Instagram একবারই চেষ্টা হয়, ব্যর্থ হলে নিজে থেকে আবার চেষ্টা হয় না। Facebook ও WordPress-এর পোস্ট ঠিকই থাকে। পোস্ট শেষের পেইজে ওই এরর লেখার নিচে 🔁 রিট্রাই বাটন থাকে, চেপে নিজে আবার চেষ্টা করুন। পোস্ট আগেই হয়ে গেলে রিট্রাই শুধু কমেন্ট/রিপ্লাই দেবে, ডুপ্লিকেট পোস্ট করবে না।",
        "দৈনিক সীমা" to "Instagram API দিয়ে প্রতি ২৪ ঘণ্টায় পোস্টের সীমা আছে (সাধারণত ১০০)। দিনে এর চেয়ে কম পোস্ট করলে সমস্যা হবে না।",
    ),
    done = "Instagram যাচাই ✅ হলে এখন থেকে Facebook-এ পোস্ট করলেই Instagram-এও ছবি ও কমেন্ট যাবে।",
)

internal val TH_GUIDE = GDoc(
    title = "Threads সংযোগ: ধাপে ধাপে",
    intro = "Threads-এর জন্য Facebook-এর টোকেন চলে না। Meta-র Threads API থেকে আলাদা Access Token লাগে। টোকেনের মেয়াদ ৬০ দিন, তবে অ্যাপ থেকেই এক চাপে বাড়ানো যায়।",
    needs = listOf(
        "একটা Threads অ্যাকাউন্ট (সাধারণত Instagram দিয়ে খোলা)।",
        "Meta for Developers অ্যাকাউন্ট (Facebook গাইডের ধাপ ১)।",
        "Threads প্রোফাইল পাবলিক রাখুন। প্রাইভেট হলে অনুমতি প্রতি ৯০ দিনে আবার দিতে হয়।",
        "সময়: প্রায় ১৫ মিনিট।",
    ),
    steps = listOf(
        GStep(
            "Threads use case যোগ করুন",
            listOf(
                "Meta for Developers-এ আপনার অ্যাপ খুলুন (Facebook-এর জন্য বানানোটাই চলবে)।",
                "বাঁ মেনুতে Use cases → Add use case চাপুন।",
                "Access the Threads API বেছে Add করুন।",
                "এই use case আপনার অ্যাপের অন্যগুলোর সাথে না মিললে Create App চেপে নতুন একটা অ্যাপ বানান, শুধু Access the Threads API বেছে নিন। Threads-এর টোকেন আলাদা, তাই আলাদা অ্যাপেও কোনো সমস্যা নেই।",
            ),
            links = listOf("Meta Apps তালিকা খুলুন" to "$DEV/apps/"),
        ),
        GStep(
            "পারমিশন চালু করুন",
            listOf(
                "Use cases → Access the Threads API → Customize চাপুন।",
                "Permissions তালিকায় threads_basic আপনা আপনি আছে। threads_content_publish আর threads_manage_replies-এর পাশের Add চাপুন।",
                "কিছুক্ষণ পর স্ট্যাটাসে Ready for testing দেখালে ঠিক আছে।",
            ),
            code = listOf("threads_basic", "threads_content_publish", "threads_manage_replies"),
        ),
        GStep(
            "নিজের Threads অ্যাকাউন্টকে Threads Tester করুন",
            listOf(
                "অ্যাপ ড্যাশবোর্ডে App roles → Roles-এ যান। Add People চেপে Threads Tester বাছুন।",
                "নিজের Threads ইউজারনেম লিখে Add চাপুন। (Customize পেইজের Settings ট্যাবেও Add or Remove Threads Testers অপশন থাকে।)",
            ),
        ),
        GStep(
            "Threads অ্যাপে আমন্ত্রণ গ্রহণ করুন",
            listOf(
                "ফোনের Threads অ্যাপ খুলুন। প্রোফাইল → উপরের মেনু → Settings → Account → Website permissions যান।",
                "Invites ট্যাবে আপনার অ্যাপের আমন্ত্রণ দেখাবে। Accept চাপুন।",
                "ওয়েবে করতে চাইলে নিচের লিংকের Website permissions → Invites-ও দেখতে পারেন।",
            ),
            links = listOf("Threads খুলুন" to "https://www.threads.com/"),
            note = "মেনুর নাম সামান্য ভিন্ন হতে পারে। Website permissions বা Invites খুঁজুন।",
        ),
        GStep(
            "Access Token বানান",
            listOf(
                "অ্যাপের Use cases → Access the Threads API → Customize → Settings ট্যাবে যান।",
                "নিচের দিকে User Token Generator প্যানেল পাবেন। সেখানে আপনার নামের পাশে Generate Access Token চাপুন।",
                "একটা অনুমতির পপআপ আসবে। একই Threads অ্যাকাউন্টে লগইন থাকা অবস্থায় Continue চাপুন, তারপর I understand টিক দিয়ে টোকেন কপি করুন।",
            ),
            note = "টোকেন একবারই দেখায়। কপি করতে ভুলে গেলে আবার Generate করুন।",
        ),
        GStep(
            "টোকেনের মেয়াদ দেখুন (জরুরি)",
            listOf(
                "নিচের Access Token Debugger-এ টোকেন বসিয়ে Debug চাপুন, Expires লাইনটা দেখুন।",
                "প্রায় ৬০ দিন দেখালে সরাসরি পরের ধাপে যান।",
                "মাত্র ১ ঘণ্টা দেখালে নিচের লিংক ব্রাউজারে বসিয়ে লম্বা মেয়াদের করে নিন। THREADS_APP_SECRET হলো App settings → Basic পেইজের “Threads App Secret” (উপরের সাধারণ App Secret নয়), আর SHORT_TOKEN হলো ১ ঘণ্টার টোকেন।",
            ),
            code = listOf(
                "https://graph.threads.com/access_token?grant_type=th_exchange_token&client_secret=THREADS_APP_SECRET&access_token=SHORT_TOKEN",
                "(না চললে graph.threads.com-এর জায়গায় graph.threads.net বসান)",
            ),
            links = listOf("Access Token Debugger খুলুন" to "$DEV/tools/debug/accesstoken/"),
            note = "ফলাফলের access_token লেখাটাই ৬০ দিনের টোকেন। এটা কারও সাথে শেয়ার করবেন না।",
        ),
        GStep(
            "অ্যাপে বসিয়ে সেভ করুন",
            listOf(
                "অ্যাপের সেটিংস → “🔑 WordPress ও Facebook সংযোগ” → Threads অংশ।",
                "Threads Access Token ঘরে টোকেন বসান।",
                "Threads User ID ঘরটা ফাঁকা রাখতে পারেন, অ্যাপ টোকেন থেকে নিজে বের করে নেয়।",
                "“💾 সংযোগ সেভ করুন” চাপুন, তারপর Threads অংশের “🔌 টেস্ট করুন” চাপুন। ✅ ও আপনার @ইউজারনেম দেখালে ঠিক আছে।",
            ),
        ),
        GStep(
            "পোস্ট করলে কী হবে",
            listOf(
                "টোকেন সেভ থাকলে পোস্ট করার আগে বাটনের উপরে “Threads-এও পোস্ট করুন” টিক বক্স থাকে (Instagram-ও সেটাপ থাকলে একসাথে “Instagram ও Threads-এও পোস্ট করুন”)। ডিফল্টে টিক দেওয়া।",
                "টিক থাকলে Facebook-এ পোস্ট সফল হওয়ার পর একই ছবি Threads-এ যায়, আর নিজের পোস্টের নিচে রিপ্লাই হিসেবে “বিস্তারিত পড়ুন: লিংক” বসে।",
                "এক্সট্রা ১ টাকা কাটে যেকোনো একটায় (Instagram বা Threads) সফল হলে, একই পোস্টে একবারই। ব্যর্থ হলে কাটে না।",
                "Threads রিপ্লাইয়ের লিংকে চাপ দেওয়া যায়।",
            ),
        ),
        GStep(
            "প্রতি ~৫০ দিনে টোকেনের মেয়াদ বাড়ান",
            listOf(
                "Threads অংশের “🔁 মেয়াদ বাড়ান” বাটন চাপুন। নতুন টোকেন নিজে সেভ হয়ে যাবে, আবার ৬০ দিনের জন্য।",
                "টোকেন অন্তত ২৪ ঘণ্টার পুরোনো হতে হবে, আর মেয়াদ শেষ হওয়ার আগে করতে হবে।",
                "মেয়াদ শেষ হয়ে গেলে বাড়ানো যায় না। তখন ধাপ ৫ থেকে নতুন করে টোকেন বানাতে হবে।",
            ),
            note = "ফোনে একটা রিমাইন্ডার রাখুন। মেয়াদ শেষ হলে Threads-এ পোস্ট চুপচাপ বন্ধ হয়ে যায়, Facebook ঠিকই চলে।",
        ),
    ),
    problems = listOf(
        "টেস্টে টোকেন ঠিক নেই বা মেয়াদ শেষ বলছে" to "ধাপ ৫ থেকে নতুন টোকেন বানিয়ে সেভ করুন।",
        "Session key invalid (কোড 452)" to "টোকেন বদলানোর সময় ভুল App Secret দিয়েছেন। App settings → Basic পেইজের নিচের Threads App Secret ব্যবহার করুন।",
        "টোকেন বানাতে গেলে আপনার নাম তালিকায় নেই" to "ধাপ ৩ ও ৪ ঠিকমতো করেছেন কিনা দেখুন: Tester হিসেবে যোগ করা এবং Threads অ্যাপে আমন্ত্রণ Accept করা।",
        "পারমিশন নেই বলছে" to "ধাপ ২-এর পারমিশন Ready for testing কিনা দেখুন। পারমিশন যোগ করার পরে নতুন টোকেন বানাতে হয়।",
        "মেয়াদ বাড়ানো যাচ্ছে না" to "টোকেন ২৪ ঘণ্টার কম পুরোনো, অথবা মেয়াদ শেষ হয়ে গেছে। নতুন করে বানান।",
        "ছবি Threads-এ যায়নি কিন্তু Facebook-এ গেছে" to "পোস্টের ফলাফলে threads_error পড়ুন। Threads একবারই চেষ্টা হয়, ব্যর্থ হলে নিজে থেকে আবার চেষ্টা হয় না। Facebook ও WordPress-এর পোস্ট ঠিকই থাকে। পোস্ট শেষের পেইজে ওই এরর লেখার নিচে 🔁 রিট্রাই বাটন থাকে, চেপে নিজে আবার চেষ্টা করুন। পোস্ট আগেই হয়ে গেলে রিট্রাই শুধু কমেন্ট/রিপ্লাই দেবে, ডুপ্লিকেট পোস্ট করবে না।",
    ),
    done = "টেস্ট ✅ হলে Threads সংযোগ শেষ। এখন Facebook-এ পোস্ট করলেই Threads-এও ছবি ও রিপ্লাই যাবে।",
)

internal val YT_GUIDE = GDoc(
    title = "YouTube সংযোগ: ধাপে ধাপে",
    intro = "ভিডিও অ্যাপ থেকে সরাসরি আপনার YouTube চ্যানেলে যায়। পাসওয়ার্ড বা সিক্রেট অ্যাপে বসাতে হয় না। শুধু Google Cloud-এ একবার একটা Android OAuth client বানাতে হয়, তাতে অ্যাপের প্যাকেজ নাম আর SHA-1 লাগে (সেটিংসের YouTube অংশে দেখানো আছে)।",
    needs = listOf(
        "যে Google অ্যাকাউন্টে আপনার YouTube চ্যানেল আছে সেটা।",
        "ফোনে Google Play services।",
        "YouTube অংশে দেখানো প্যাকেজ নাম ও SHA-1 (কপি করে রাখুন)।",
    ),
    steps = listOf(
        GStep(
            "Google Cloud Console-এ প্রজেক্ট নিন",
            listOf(
                "চ্যানেলের Google অ্যাকাউন্টে লগইন করুন। আগে প্রজেক্ট বানানো থাকলে সেটাই ব্যবহার করুন, নইলে New Project চাপুন।",
            ),
            links = listOf("Google Cloud Console খুলুন" to "https://console.cloud.google.com/"),
        ),
        GStep(
            "YouTube Data API v3 চালু করুন",
            listOf(
                "APIs & Services → Library খুলুন, YouTube Data API v3 খুঁজে Enable চাপুন। আগে করা থাকলে লাগবে না।",
            ),
        ),
        GStep(
            "OAuth consent screen ঠিক করুন",
            listOf(
                "OAuth consent screen (নতুন ডিজাইনে Google Auth Platform) খুলুন। User type: External।",
                "অ্যাপের নাম ও ইমেইল দিন। Scopes-এ .../auth/youtube.upload যোগ করুন।",
                "Test users-এ নিজের Google ইমেইল দিন। অ্যাপ Testing অবস্থায় রাখলেই চলবে।",
                "আগে ওয়েব অ্যাপের জন্য করা থাকলে এই ধাপ আবার লাগবে না।",
            ),
        ),
        GStep(
            "Android OAuth client বানান",
            listOf(
                "Credentials → Create Credentials → OAuth client ID চাপুন।",
                "Application type: Android বাছুন।",
                "Package name আর SHA-1 ঘরে অ্যাপের YouTube অংশে দেখানো দুটো মান হুবহু বসান। Create চাপুন।",
                "কোনো কিছু কপি করে অ্যাপে দিতে হবে না।",
            ),
        ),
        GStep(
            "অ্যাপে লগইন টেস্ট করুন",
            listOf(
                "অ্যাপের YouTube অংশে “🔌 লগইন টেস্ট” চাপুন। Google পপআপে চ্যানেলের অ্যাকাউন্ট বাছুন।",
                "“Google hasn’t verified this app” দেখালে Advanced → Go to ... চেপে অনুমতি দিন। এটা আপনার নিজের অ্যাপ, তাই নিরাপদ।",
                "সফল হলে ✅ লগইন ঠিক আছে দেখাবে।",
            ),
        ),
    ),
    problems = listOf(
        "ভিডিও আপলোড হয়ে প্রাইভেট থেকে যাচ্ছে" to "নতুন API প্রজেক্ট থেকে আপলোড করা ভিডিও Google অডিট না করা পর্যন্ত প্রাইভেট থাকতে পারে। YouTube Studio থেকে হাতে Public করুন, অথবা Google-এর YouTube API Services Audit ফর্ম জমা দিন।",
        "দৈনিক সীমা" to "ডিফল্ট কোটায় দিনে প্রায় ৬টা ভিডিও আপলোড করা যায়।",
        "লগইন কাজ করছে না" to "অ্যাপ নতুন keystore দিয়ে বিল্ড হলে SHA-1 বদলে যায়। তখন ধাপ ৪ আবার করে নতুন SHA-1 দিন।",
    ),
    done = "লগইন টেস্ট ✅ হলে ভিডিও পাবলিশে ওই চ্যানেলে যাবে।",
)
