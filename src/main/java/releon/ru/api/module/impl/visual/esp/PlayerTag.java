package releon.ru.api.module.impl.visual.esp;

import java.util.List;

record PlayerTag(List<TagPart> voice, List<TagPart> donation, List<TagPart> prefix, List<TagPart> name, List<TagPart> suffix) {
}
