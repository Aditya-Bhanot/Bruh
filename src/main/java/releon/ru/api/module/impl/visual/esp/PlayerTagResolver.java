package releon.ru.api.module.impl.visual.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import releon.ru.utils.network.Network;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class PlayerTagResolver {
    PlayerTag resolve(Player player, Minecraft mc) {
        PlayerTeam team = resolvePlayerTeam(player);
        PlayerInfo playerInfo = resolvePlayerInfo(player, mc);
        PlayerTag tag;
        if (Network.isReallyWorld()) {
            tag = resolveReallyWorldPlayerTag(player, team, playerInfo);
            return stripDuplicateVoiceStatus(tag);
        } else {
            tag = resolveStyledPlayerTag(player, playerInfo, team);
            return stripVoiceStatus(tag);
        }
    }

    private PlayerTag resolveReallyWorldPlayerTag(Player player, PlayerTeam team, PlayerInfo playerInfo) {
        String donation = decodeReallyWorldTag(team == null ? null : team.getPlayerPrefix().getString(), true);
        String suffix = decodeReallyWorldTag(team == null ? null : team.getPlayerSuffix().getString(), true);
        String name = decodeReallyWorldTag(player.getName().getString(), false);
        if (name.isBlank()) {
            name = decodeReallyWorldTag(player.getScoreboardName(), false);
        }
        if (name.isBlank()) {
            name = "Player";
        }

        return new PlayerTag(
                resolveVoiceStatusParts(player, playerInfo),
                TagParts.list(FontType.BOLD, donation, EspColors.TAG_MUTED),
                Collections.emptyList(),
                TagParts.list(FontType.SEMIBOLD, name, EspColors.TAG_TEXT),
                TagParts.list(FontType.SEMIBOLD, suffix, EspColors.TAG_MUTED)
        );
    }

    private PlayerInfo resolvePlayerInfo(Player player, Minecraft mc) {
        if (player == null || mc.getConnection() == null) {
            return null;
        }
        return mc.getConnection().getPlayerInfo(player.getUUID());
    }

    private PlayerTag resolveStyledPlayerTag(Player player, PlayerInfo playerInfo, PlayerTeam team) {
        Component tabName = playerInfo == null ? null : playerInfo.getTabListDisplayName();
        PlayerTag tabTag = resolvePlayerTagFromDisplay(player, playerInfo, tabName);
        if (tabTag != null) {
            return tabTag;
        }

        Component displayName = player == null ? null : player.getDisplayName();
        PlayerTag displayTag = resolvePlayerTagFromDisplay(player, playerInfo, displayName);
        if (displayTag != null) {
            return displayTag;
        }

        Component teamName = team == null ? null : PlayerTeam.formatNameForTeam(team, Component.literal(player.getScoreboardName()));
        PlayerTag teamTag = resolvePlayerTagFromDisplay(player, playerInfo, teamName);
        if (teamTag != null) {
            return teamTag;
        }

        VoiceDonationPrefix prefix = splitVoiceDonationPrefix(componentToTagParts(team == null ? null : team.getPlayerPrefix(), FontType.BOLD, EspColors.TAG_MUTED));
        List<TagPart> suffix = componentToTagParts(team == null ? null : team.getPlayerSuffix(), FontType.SEMIBOLD, EspColors.TAG_MUTED);
        return new PlayerTag(
                resolveVoiceStatusParts(player, playerInfo),
                TagParts.retagFont(TagParts.stripOuterDecorators(prefix.donation()), FontType.BOLD),
                TagParts.retagFont(prefix.prefix(), FontType.SEMIBOLD),
                TagParts.list(FontType.SEMIBOLD, resolvePlainPlayerName(player, playerInfo), EspColors.TAG_TEXT),
                TagParts.retagFont(TagParts.trim(suffix), FontType.SEMIBOLD)
        );
    }

    private PlayerTag resolvePlayerTagFromDisplay(Player player, PlayerInfo playerInfo, Component displayName) {
        List<TagPart> displayParts = TagParts.trim(componentToTagParts(displayName, FontType.SEMIBOLD, EspColors.TAG_TEXT));
        if (displayParts.isEmpty()) {
            return null;
        }

        String plain = TagParts.plainText(displayParts);
        NameRange nameRange = findNameRange(plain, playerNameCandidates(player, playerInfo));
        if (nameRange == null) {
            return null;
        }

        VoiceDonationPrefix prefix = splitVoiceDonationPrefix(TagParts.slice(displayParts, 0, nameRange.start()));
        List<TagPart> name = TagParts.trim(TagParts.slice(displayParts, nameRange.start(), nameRange.end()));
        List<TagPart> suffix = TagParts.trim(TagParts.slice(displayParts, nameRange.end(), TagParts.textLength(displayParts)));
        if (name.isEmpty()) {
            name = TagParts.list(FontType.SEMIBOLD, resolvePlainPlayerName(player, playerInfo), EspColors.TAG_TEXT);
        }

        return new PlayerTag(
                prefix.voice(),
                TagParts.retagFont(TagParts.stripOuterDecorators(prefix.donation()), FontType.BOLD),
                TagParts.retagFont(prefix.prefix(), FontType.SEMIBOLD),
                TagParts.retagFont(name, FontType.SEMIBOLD),
                TagParts.retagFont(suffix, FontType.SEMIBOLD)
        );
    }

    private PlayerTag withFriendPrefix(Player player, PlayerTag tag) {
        if (tag == null) {
            tag = new PlayerTag(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    TagParts.list(FontType.SEMIBOLD, resolvePlainPlayerName(player, null), EspColors.TAG_TEXT),
                    Collections.emptyList()
            );
        }
        if (!FriendUtils.isFriend(player)) {
            return tag;
        }

        return new PlayerTag(
                tag.voice(),
                tag.donation(),
                TagParts.join(TagParts.list(FontType.BOLD, "Friend", EspColors.TAG_MUTED), tag.prefix()),
                tag.name(),
                tag.suffix()
        );
    }

    private PlayerTag stripDuplicateVoiceStatus(PlayerTag tag) {
        if (tag == null) {
            return null;
        }

        VoiceStatusSplit donation = splitExplicitVoiceStatus(tag.donation());
        VoiceStatusSplit prefix = splitExplicitVoiceStatus(tag.prefix());
        VoiceStatusSplit name = splitExplicitVoiceStatus(tag.name());
        VoiceStatusSplit suffix = splitExplicitVoiceStatus(tag.suffix());
        List<TagPart> voice = tag.voice().isEmpty()
                ? firstVoice(donation.voice(), prefix.voice(), name.voice(), suffix.voice())
                : tag.voice();

        return new PlayerTag(voice, donation.remaining(), prefix.remaining(), name.remaining(), suffix.remaining());
    }

    private PlayerTag stripVoiceStatus(PlayerTag tag) {
        if (tag == null) {
            return null;
        }

        return new PlayerTag(
                Collections.emptyList(),
                splitExplicitVoiceStatus(tag.donation()).remaining(),
                splitExplicitVoiceStatus(tag.prefix()).remaining(),
                splitExplicitVoiceStatus(tag.name()).remaining(),
                splitExplicitVoiceStatus(tag.suffix()).remaining()
        );
    }

    @SafeVarargs
    private final List<TagPart> firstVoice(List<TagPart>... candidates) {
        for (List<TagPart> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return Collections.emptyList();
    }

    private String resolvePlainPlayerName(Player player, PlayerInfo playerInfo) {
        String name = player == null ? "" : TagParts.clean(player.getName().getString());
        if (name.isBlank() && player != null) {
            name = TagParts.clean(player.getScoreboardName());
        }
        if (name.isBlank() && playerInfo != null && playerInfo.getProfile() != null) {
            name = TagParts.clean(playerInfo.getProfile().name());
        }
        return name.isBlank() ? "Player" : name;
    }

    private List<String> playerNameCandidates(Player player, PlayerInfo playerInfo) {
        List<String> candidates = new ArrayList<>(3);
        if (player != null) {
            addNameCandidate(candidates, TagParts.clean(player.getScoreboardName()));
            addNameCandidate(candidates, TagParts.clean(player.getName().getString()));
        }
        if (playerInfo != null && playerInfo.getProfile() != null) {
            addNameCandidate(candidates, TagParts.clean(playerInfo.getProfile().name()));
        }
        candidates.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return candidates;
    }

    private void addNameCandidate(List<String> candidates, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(name)) {
                return;
            }
        }
        candidates.add(name);
    }

    private NameRange findNameRange(String plain, List<String> candidates) {
        if (plain == null || plain.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }

        String lowerPlain = plain.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
            int index = lowerPlain.indexOf(lowerCandidate);
            if (index >= 0) {
                return new NameRange(index, index + candidate.length());
            }
        }
        return null;
    }

    private List<TagPart> resolveVoiceStatusParts(Player player, PlayerInfo playerInfo) {
        if (playerInfo == null) {
            return Collections.emptyList();
        }

        List<TagPart> displayParts = TagParts.trim(componentToTagParts(playerInfo.getTabListDisplayName(), FontType.SEMIBOLD, EspColors.TAG_MUTED));
        if (displayParts.isEmpty()) {
            return Collections.emptyList();
        }

        String plain = TagParts.plainText(displayParts);
        NameRange nameRange = findNameRange(plain, playerNameCandidates(player, playerInfo));
        List<TagPart> beforeName = nameRange == null ? displayParts : TagParts.slice(displayParts, 0, nameRange.start());
        return splitVoiceStatus(beforeName).voice();
    }

    private VoiceDonationPrefix splitVoiceDonationPrefix(List<TagPart> source) {
        VoiceStatusSplit voice = splitVoiceStatus(source);
        DonationPrefix prefix = splitDonationPrefix(voice.remaining());
        return new VoiceDonationPrefix(voice.voice(), prefix.donation(), prefix.prefix());
    }

    private VoiceStatusSplit splitVoiceStatus(List<TagPart> source) {
        return splitVoiceStatus(source, true);
    }

    private VoiceStatusSplit splitExplicitVoiceStatus(List<TagPart> source) {
        return splitVoiceStatus(source, false);
    }

    private VoiceStatusSplit splitVoiceStatus(List<TagPart> source, boolean allowColorFallback) {
        List<TagPart> parts = TagParts.trim(source);
        int index = findVoiceStatusIndex(parts, allowColorFallback);
        if (index < 0) {
            return new VoiceStatusSplit(Collections.emptyList(), parts);
        }

        int color = TagParts.colorAt(parts, index, EspColors.TAG_MUTED);
        List<TagPart> voice = List.of(new TagPart(FontType.SEMIBOLD, "\u25CF", color));
        List<TagPart> remaining = TagParts.concat(
                TagParts.slice(parts, 0, index),
                TagParts.slice(parts, index + 1, TagParts.textLength(parts))
        );
        return new VoiceStatusSplit(voice, TagParts.trim(remaining));
    }

    private int findVoiceStatusIndex(List<TagPart> parts, boolean allowColorFallback) {
        String plain = TagParts.plainText(parts);
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isWhitespace(c) || TagParts.isLooseDecorator(c)) {
                continue;
            }
            if (getIconLabel(c) != null) {
                return -1;
            }
            if (isVoiceStatusGlyph(c)) {
                return i;
            }
            if (allowColorFallback && !Character.isLetterOrDigit(c) && c != '_' && TagParts.colorAt(parts, i, EspColors.TAG_MUTED) != EspColors.TAG_MUTED) {
                return i;
            }
            return -1;
        }
        return -1;
    }

    private boolean isVoiceStatusGlyph(char c) {
        return c == '\u25CF'
                || c == '\u2022'
                || c == '\u26AB'
                || c == '\u25C9'
                || c == '\u25CB'
                || c == '\u25A0'
                || c == '\u25AA'
                || c == '\u25C6'
                || c == '\u2666'
                || c == '\u271A'
                || c == '+';
    }

    private DonationPrefix splitDonationPrefix(List<TagPart> source) {
        List<TagPart> parts = TagParts.trim(source);
        if (parts.isEmpty()) {
            return new DonationPrefix(Collections.emptyList(), Collections.emptyList());
        }

        List<TagPart> compactParts = TagParts.compactToSingleTextPart(TagParts.stripOuterDecorators(parts), FontType.BOLD, EspColors.TAG_MUTED);
        if (!TagParts.plainText(compactParts).equals(TagParts.plainText(TagParts.stripOuterDecorators(parts)))) {
            return new DonationPrefix(compactParts, Collections.emptyList());
        }

        String plain = TagParts.plainText(parts);
        int donationEnd = findDonationEnd(plain);
        if (donationEnd <= 0 || donationEnd >= plain.length()) {
            return new DonationPrefix(compactParts, Collections.emptyList());
        }

        List<TagPart> donation = TagParts.compactToSingleTextPart(TagParts.stripOuterDecorators(TagParts.slice(parts, 0, donationEnd)), FontType.BOLD, EspColors.TAG_MUTED);
        List<TagPart> prefix = TagParts.compactToSingleTextPart(TagParts.stripOuterDecorators(TagParts.trim(TagParts.slice(parts, donationEnd, TagParts.textLength(parts)))), FontType.SEMIBOLD, EspColors.TAG_MUTED);
        return new DonationPrefix(donation, prefix);
    }

    private int findDonationEnd(String plain) {
        if (plain == null || plain.isBlank()) {
            return 0;
        }

        int start = 0;
        int length = plain.length();
        while (start < length && Character.isWhitespace(plain.charAt(start))) {
            start++;
        }
        if (start >= length) {
            return 0;
        }

        char first = plain.charAt(start);
        if (TagParts.isOpeningDecorator(first)) {
            char closing = matchingClosingDecorator(first);
            int closingIndex = closing == 0 ? -1 : plain.indexOf(closing, start + 1);
            if (closingIndex >= 0) {
                return closingIndex + 1;
            }
        }
        if (first == '\u00AB') {
            int closingIndex = plain.indexOf('\u00BB', start + 1);
            if (closingIndex >= 0) {
                return closingIndex + 1;
            }
        }

        int letterSpacedEnd = findLeadingLetterSpacedDonationEnd(plain, start);
        if (letterSpacedEnd > start) {
            return letterSpacedEnd;
        }

        int end = start;
        while (end < length) {
            char c = plain.charAt(end);
            if (Character.isWhitespace(c) || c == '|' || c == ':') {
                break;
            }
            end++;
        }
        return Math.max(start, end);
    }

    private int findLeadingLetterSpacedDonationEnd(String plain, int start) {
        int index = start;
        int end = start;
        int tokenCount = 0;
        int length = plain.length();

        while (index < length) {
            int spaceStart = index;
            while (index < length && Character.isWhitespace(plain.charAt(index))) {
                index++;
            }
            if (tokenCount > 0 && index == spaceStart) {
                break;
            }
            if (index >= length) {
                break;
            }

            int tokenStart = index;
            while (index < length && !Character.isWhitespace(plain.charAt(index)) && plain.charAt(index) != '|' && plain.charAt(index) != ':') {
                index++;
            }
            if (index - tokenStart != 1 || !TagParts.isCompactPrefixChar(plain.charAt(tokenStart))) {
                break;
            }

            tokenCount++;
            end = index;
        }
        return tokenCount >= 2 ? end : -1;
    }

    private char matchingClosingDecorator(char c) {
        return switch (c) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> 0;
        };
    }

    private List<TagPart> componentToTagParts(Component component, FontType font, int fallbackColor) {
        if (component == null) {
            return Collections.emptyList();
        }

        List<TagPart> parts = new ArrayList<>();
        component.visit((style, text) -> {
            appendComponentText(parts, font, text, colorFromStyle(style, fallbackColor));
            return Optional.empty();
        }, Style.EMPTY);
        if (parts.isEmpty()) {
            appendComponentText(parts, font, component.getString(), colorFromStyle(component.getStyle(), fallbackColor));
        }
        return TagParts.trim(parts);
    }

    private void appendComponentText(List<TagPart> parts, FontType font, String text, int fallbackColor) {
        if (text == null || text.isEmpty()) {
            return;
        }

        int currentColor = fallbackColor;
        for (int i = 0; i < text.length(); i++) {
            LegacyFormatResult legacy = consumeLegacyFormat(text, i, fallbackColor, currentColor);
            if (legacy != null) {
                currentColor = legacy.color();
                i = legacy.nextIndex();
                continue;
            }

            String token = normalizeTagChar(text.charAt(i));
            if (token == null || token.isEmpty()) {
                continue;
            }
            TagParts.appendRaw(parts, font, token, currentColor);
        }
    }

    private LegacyFormatResult consumeLegacyFormat(String text, int index, int fallbackColor, int currentColor) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }

        char current = text.charAt(index);
        if (current == '\u00C2' && index + 1 < text.length() && text.charAt(index + 1) == '\u00A7') {
            index++;
            current = '\u00A7';
        }
        if (current != '\u00A7' || index + 1 >= text.length()) {
            return null;
        }

        char code = Character.toLowerCase(text.charAt(index + 1));
        if (code == 'x' && index + 13 < text.length()) {
            StringBuilder hex = new StringBuilder(6);
            for (int i = index + 2; i <= index + 12; i += 2) {
                if (text.charAt(i) != '\u00A7') {
                    return new LegacyFormatResult(currentColor, index + 1);
                }
                hex.append(text.charAt(i + 1));
            }
            try {
                return new LegacyFormatResult(0xFF000000 | Integer.parseInt(hex.toString(), 16), index + 13);
            } catch (NumberFormatException ignored) {
                return new LegacyFormatResult(currentColor, index + 1);
            }
        }
        if (code == '#' && index + 7 < text.length()) {
            try {
                return new LegacyFormatResult(0xFF000000 | Integer.parseInt(text.substring(index + 2, index + 8), 16), index + 7);
            } catch (NumberFormatException ignored) {
                return new LegacyFormatResult(currentColor, index + 1);
            }
        }

        Integer legacyColor = switch (code) {
            case '0' -> 0xFF000000;
            case '1' -> 0xFF0000AA;
            case '2' -> 0xFF00AA00;
            case '3' -> 0xFF00AAAA;
            case '4' -> 0xFFAA0000;
            case '5' -> 0xFFAA00AA;
            case '6' -> 0xFFFFAA00;
            case '7' -> 0xFFAAAAAA;
            case '8' -> 0xFF555555;
            case '9' -> 0xFF5555FF;
            case 'a' -> 0xFF55FF55;
            case 'b' -> 0xFF55FFFF;
            case 'c' -> 0xFFFF5555;
            case 'd' -> 0xFFFF55FF;
            case 'e' -> 0xFFFFFF55;
            case 'f' -> 0xFFFFFFFF;
            case 'r' -> fallbackColor;
            default -> null;
        };
        return new LegacyFormatResult(legacyColor == null ? currentColor : legacyColor, index + 1);
    }

    private String normalizeTagChar(char value) {
        if (value == 0 || Character.isISOControl(value)) {
            return "";
        }
        if (value == '\u00C2' || value == '\u00A0' || value == '\u200B' || value == '\uFEFF' || Character.isWhitespace(value)) {
            return " ";
        }
        return String.valueOf(value);
    }

    private int colorFromStyle(Style style, int fallbackColor) {
        if (style != null && style.getColor() != null) {
            return 0xFF000000 | style.getColor().getValue();
        }
        return fallbackColor;
    }

    private PlayerTeam resolvePlayerTeam(Player player) {
        if (player == null || player.level() == null) {
            return null;
        }

        Scoreboard scoreboard = player.level().getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (team == null) {
            team = scoreboard.getPlayersTeam(player.getName().getString());
        }
        return team;
    }

    private String decodeReallyWorldTag(String text, boolean stripDecorators) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        boolean skipFormatting = false;
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (skipFormatting) {
                skipFormatting = false;
                continue;
            }
            if (c == '\u00A7') {
                skipFormatting = true;
                continue;
            }
            if (c == '\u00C2' || c == '\u00A0' || c == '\u200B' || c == '\uFEFF' || Character.isWhitespace(c)) {
                pendingSpace = builder.length() > 0;
                continue;
            }

            String token = getIconLabel(c);
            if (token == null) {
                token = normalizeReallyWorldChar(c);
            }
            if (token == null || token.isBlank()) {
                continue;
            }
            if (pendingSpace && builder.length() > 0 && !startsWithDecorator(token)) {
                builder.append(' ');
            }
            builder.append(token);
            pendingSpace = false;
        }

        String result = TagParts.clean(builder.toString());
        return stripDecorators ? stripTagDecorators(result) : result;
    }

    private boolean startsWithDecorator(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char c = token.charAt(0);
        return c == ']' || c == ')' || c == '}' || c == '>' || c == ':' || c == '|';
    }

    private String stripTagDecorators(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String result = text.trim();
        boolean changed;
        do {
            changed = false;
            if (result.length() >= 2 && TagParts.isOpeningDecorator(result.charAt(0)) && TagParts.isClosingDecorator(result.charAt(result.length() - 1))) {
                result = result.substring(1, result.length() - 1).trim();
                changed = true;
            }
            while (!result.isEmpty() && TagParts.isLooseDecorator(result.charAt(0))) {
                result = result.substring(1).trim();
                changed = true;
            }
            while (!result.isEmpty() && TagParts.isLooseDecorator(result.charAt(result.length() - 1))) {
                result = result.substring(0, result.length() - 1).trim();
                changed = true;
            }
        } while (changed);
        return result;
    }

    private String normalizeReallyWorldChar(char value) {
        if (Character.isISOControl(value)) {
            return "";
        }
        return switch (value) {
            case '\u0262' -> "G";
            case '\u029C' -> "H";
            case '\u026A' -> "I";
            case '\u0274' -> "N";
            case '\u0280' -> "R";
            case '\u028F' -> "Y";
            case '\u1D05' -> "D";
            case '\u1D07' -> "E";
            case '\u1D0A' -> "J";
            case '\u1D0B' -> "K";
            case '\u1D0D' -> "M";
            case '\u1D0F' -> "O";
            case '\u1D18' -> "P";
            case '\u1D1B' -> "T";
            case '\u1D1C' -> "U";
            case '\u1D20' -> "V";
            case '\u1D21' -> "W";
            case '\u1D00' -> "A";
            case '\u0299' -> "B";
            case '\u1D04' -> "C";
            case '\uA731' -> "S";
            case '\u0493' -> "F";
            case '\u029F' -> "L";
            case '\u01EB' -> "Q";
            default -> String.valueOf(value);
        };
    }

    private String getIconLabel(char c) {
        return switch (c) {
            case '\uA500' -> "PLAYER";
            case '\uA504' -> "HERO";
            case '\uA508' -> "TITAN";
            case '\uA512' -> "AVENGER";
            case '\uA516' -> "OVERLORD";
            case '\uA520' -> "MAGISTER";
            case '\uA524' -> "IMPERATOR";
            case '\uA528' -> "DRAGON";
            case '\uA532' -> "BULL";
            case '\uA552' -> "RABBIT";
            case '\uA536' -> "TIGER";
            case '\uA544' -> "DRACULA";
            case '\uA556' -> "BUNNY";
            case '\uA540' -> "HYDRA";
            case '\uA548' -> "COBRA";
            case '\uA501' -> "MEDIA";
            case '\uA505' -> "YT";
            case '\uA560' -> "D.HELPER";
            case '\uA509' -> "HELPER";
            case '\uA513' -> "ML.MODER";
            case '\uA517' -> "MODER";
            case '\uA521' -> "MODER+";
            case '\uA525' -> "ST.MODER";
            case '\uA529' -> "GL.MODER";
            case '\uA533' -> "ML.ADMIN";
            case '\uA537' -> "ADMIN";
            case '\uA545' -> "VAMPIRE";
            case '\uA549' -> "PEGAS";
            default -> null;
        };
    }

    private record DonationPrefix(List<TagPart> donation, List<TagPart> prefix) {
    }

    private record VoiceDonationPrefix(List<TagPart> voice, List<TagPart> donation, List<TagPart> prefix) {
    }

    private record VoiceStatusSplit(List<TagPart> voice, List<TagPart> remaining) {
    }

    private record NameRange(int start, int end) {
    }

    private record LegacyFormatResult(int color, int nextIndex) {
    }
}
