package me.furio.utils;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;

/**
 * Created by furione on 05/11/16.
 */

// Na cagata per le language pair xD
public class LanguagePair {
    private static String[] RFCList = new String[] {"af-ZA","sq-AL","am-ET","ar-SA","hy-AM","az-AZ","bjs-BB","eu-ES",
            "bem-ZM","bn-IN","be-BY","my-MM","bi-VU","bs-BA","br-FR","bg-BG","kab-DZ","ca-ES","cb-PH","cs-CZ","ch-GU",
            "ky-KG","zh-CN","zh-TW","zdj-KM","cop-EG","ko-KR","kea-CV","ht-HT","acf-LC","crs-SC","aig-AG","bah-BS",
            "jam-JM","gcl-GD","gyn-GY","vic-US","svc-VC","pov-GW","hr-HR","ku-TR","ku-TR","da-DK","dz-BT","he-IL",
            "eo-EU","et-EE","fn-FNG","fo-FO","fi-FI","fr-FR","fr-BE","ga-IE","gv-IM","gd-GB","gl-ES","cy-GB","ka-GE",
            "ja-JA","jw-ID","rm-RO","el-GR","grc-GR","gu-IN","ha-NE","haw-US","hi-IN","id-ID","en-GB","kl-GL","is-IS",
            "it-IT","ka-IN","kk-KZ","km-KM","rw-RW","rn-RN","lo-LA","la-VA","lv-LV","ti-TI","lt-LT","lb-LU","mk-MK",
            "dv-MV","ms-MY","mg-MG","mt-MT","mi-NZ","mh-MH","mfe-MU","men-SL","mn-MN","ne-NP","niu-NU","no-NO","ny-MW",
            "nl-NL","ur-PK","pau-PW","pa-IN","pap-PAP","ps-PK","fa-IR","pis-SB","pl-PL","pt-PT","pot-US","qu-PE",
            "ro-RO","ru-RU","sm-WS","sg-CF","sr-RS","sn-ZW","si-LK","syc-TR","sk-SK","sl-SI","so-SO","nso-ZA","es-ES",
            "srn-SR","sv-SE","sw-SZ","tl-PH","tg-TJ","th-TH","tmh-DZ","ta-LK","de-DE","de-CH","te-IN","tet-TL","bo-CN",
            "tpi-PG","tkl-TK","to-TO","tn-BW","tr-TR","tk-TM","tvl-TV","uk-UA","ppk-ID","hu-HU","uz-UZ","vi-VN",
            "wls-WF","wo-SN","xh-ZA","yi-YD","zu-ZA"};


    private static Set<String> RFCSet = Arrays.stream(RFCList).collect(Collectors.toSet());
    private static Set<String> LangSet = Arrays.stream(RFCList).map(el -> el.split("-")[0]).collect(Collectors.toSet());


    public static LanguagePair fromQueryString(String input, String pairSeparator) throws Exception {
        if (input.indexOf(pairSeparator) == -1) {
            throw new Exception("Invalid language pair " + input + " separator is absent");
        }

        String[] splittedPair = input.split(Pattern.quote(pairSeparator));
        if (splittedPair.length != 2) {
            throw new Exception("Invalid language pair " + input + " not enough languages");
        }

        Supplier<Stream<String>> streamData = () -> Arrays.stream(splittedPair);

        if ( streamData.get().map(x -> RFCSet.contains(x)).anyMatch(x -> false) &&
                streamData.get().map(x -> LangSet.contains(x)).anyMatch(x -> false) ) {
            throw new Exception("Invalid language pair " + input + " unsupported language(s)");
        }

        return new LanguagePair() {{
            this.sourceLanguage = splittedPair[0];
            this.targetLanguage = splittedPair[1];
        }};
    }

    public String sourceLanguage;
    public String targetLanguage;
}
