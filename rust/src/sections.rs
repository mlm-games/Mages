use std::collections::BTreeMap;

use matrix_sdk::ruma::events::{
    False, GlobalAccountDataEventContent, GlobalAccountDataEventType, StaticEventContent,
};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::SpaceSection;

const WEB_SETTINGS: &str = "im.vector.web.settings";
const CUSTOM_SECTION_DATA: &str = "RoomList.CustomSectionData";
const ORDERED_SECTIONS: &str = "RoomList.OrderedCustomSections";

const CHATS_TAG: &str = "chats";

pub const SECTION_TAG_PREFIX: &str = "element.io.section.";

pub fn is_section_tag(tag: &str) -> bool {
    tag.starts_with(SECTION_TAG_PREFIX)
}

pub fn new_tag() -> String {
    format!("{SECTION_TAG_PREFIX}{}", uuid::Uuid::new_v4().simple())
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(transparent)]
pub struct WebSettings(pub Map<String, Value>);

impl StaticEventContent for WebSettings {
    const TYPE: &'static str = WEB_SETTINGS;
    type IsPrefix = False;
}

impl GlobalAccountDataEventContent for WebSettings {
    fn event_type(&self) -> GlobalAccountDataEventType {
        WEB_SETTINGS.into()
    }
}

pub fn sections(settings: &WebSettings) -> Vec<SpaceSection> {
    let mut defined: BTreeMap<&str, SpaceSection> = BTreeMap::new();

    if let Some(data) = settings.0.get(CUSTOM_SECTION_DATA).and_then(Value::as_object) {
        for (tag, value) in data {
            let Some(entry) = value.as_object() else { continue };
            if !is_section_tag(tag) || entry.get("tag").and_then(Value::as_str) != Some(tag) {
                continue;
            }
            let Some(name) = entry.get("name").and_then(Value::as_str) else { continue };
            defined.insert(
                tag.as_str(),
                SpaceSection {
                    tag: tag.to_owned(),
                    name: name.to_owned(),
                    space_id: entry.get("spaceId").and_then(Value::as_str).map(str::to_owned),
                },
            );
        }
    }

    let mut ordered = Vec::with_capacity(defined.len());
    if let Some(order) = settings.0.get(ORDERED_SECTIONS).and_then(Value::as_array) {
        for entry in order {
            if let Some(section) = entry.as_str().and_then(|tag| defined.remove(tag)) {
                ordered.push(section);
            }
        }
    }
    ordered.extend(defined.into_values());
    ordered
}

pub fn insert(settings: &mut WebSettings, section: &SpaceSection) -> Result<(), String> {
    if !is_section_tag(&section.tag) {
        return Err(format!("not a section tag: {}", section.tag));
    }
    let data = data_mut(settings)?;
    let mut entry = Map::new();
    entry.insert("tag".into(), json!(section.tag));
    entry.insert("name".into(), json!(section.name));
    if let Some(space_id) = &section.space_id {
        entry.insert("spaceId".into(), json!(space_id));
    }
    data.insert(section.tag.clone(), Value::Object(entry));

    let order = order_mut(settings)?;
    order.retain(|entry| entry.as_str() != Some(section.tag.as_str()));
    let insert_at = order
        .iter()
        .position(|entry| entry.as_str() == Some(CHATS_TAG))
        .unwrap_or(order.len());
    order.insert(insert_at, json!(section.tag));
    Ok(())
}

pub fn rename(settings: &mut WebSettings, tag: &str, name: &str) -> Result<(), String> {
    let data = data_mut(settings)?;
    let entry = data
        .get_mut(tag)
        .and_then(Value::as_object_mut)
        .ok_or_else(|| format!("unknown section: {tag}"))?;
    entry.insert("name".into(), json!(name));
    Ok(())
}

pub fn remove(settings: &mut WebSettings, tag: &str) -> Result<(), String> {
    let removed = data_mut(settings)?.remove(tag).is_some();
    let order = order_mut(settings)?;
    order.retain(|entry| entry.as_str() != Some(tag));
    if removed { Ok(()) } else { Err(format!("unknown section: {tag}")) }
}

pub fn move_section(settings: &mut WebSettings, tag: &str, index: usize) -> Result<(), String> {
    let current = sections(settings).into_iter().map(|section| section.tag).collect::<Vec<_>>();
    let from = current
        .iter()
        .position(|existing| existing == tag)
        .ok_or_else(|| format!("unknown section: {tag}"))?;

    let mut moved = current;
    moved.remove(from);
    moved.insert(index.min(moved.len()), tag.to_owned());

    let order = order_mut(settings)?;
    let anchor = order
        .iter()
        .position(|entry| entry.as_str().is_some_and(is_section_tag));
    order.retain(|entry| !entry.as_str().is_some_and(is_section_tag));
    let at = match anchor {
        Some(at) => at.min(order.len()),
        None => order
            .iter()
            .position(|entry| entry.as_str() == Some(CHATS_TAG))
            .unwrap_or(order.len()),
    };
    for (offset, entry) in moved.iter().enumerate() {
        order.insert(at + offset, json!(entry));
    }
    Ok(())
}

fn data_mut(settings: &mut WebSettings) -> Result<&mut Map<String, Value>, String> {
    settings
        .0
        .entry(CUSTOM_SECTION_DATA)
        .or_insert_with(|| Value::Object(Map::new()))
        .as_object_mut()
        .ok_or_else(|| format!("{CUSTOM_SECTION_DATA} is not an object"))
}

fn order_mut(settings: &mut WebSettings) -> Result<&mut Vec<Value>, String> {
    settings
        .0
        .entry(ORDERED_SECTIONS)
        .or_insert_with(|| Value::Array(Vec::new()))
        .as_array_mut()
        .ok_or_else(|| format!("{ORDERED_SECTIONS} is not an array"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn settings_from(value: Value) -> WebSettings {
        WebSettings(value.as_object().cloned().unwrap_or_default())
    }

    fn section(tag: &str, name: &str) -> SpaceSection {
        SpaceSection { tag: tag.into(), name: name.into(), space_id: Some("!space".into()) }
    }

    #[test]
    fn sections_follow_the_stored_order_and_keep_unknown_ones_last() {
        let mut settings = WebSettings::default();
        insert(&mut settings, &section("element.io.section.a", "Work")).unwrap();
        insert(&mut settings, &section("element.io.section.b", "Play")).unwrap();
        insert(&mut settings, &section("element.io.section.c", "Later")).unwrap();

        move_section(&mut settings, "element.io.section.c", 0).unwrap();

        let names: Vec<_> = sections(&settings).into_iter().map(|s| s.name).collect();
        assert_eq!(names, ["Later", "Work", "Play"]);
    }

    #[test]
    fn sections_without_a_stored_order_still_come_back() {
        let settings = settings_from(json!({
            "RoomList.CustomSectionData": {
                "element.io.section.a": { "tag": "element.io.section.a", "name": "Work" }
            }
        }));
        let found = sections(&settings);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].name, "Work");
        assert_eq!(found[0].space_id, None);
    }

    #[test]
    fn malformed_entries_are_dropped_instead_of_hiding_the_whole_list() {
        let settings = settings_from(json!({
            "RoomList.CustomSectionData": {
                "element.io.section.a": { "tag": "element.io.section.a", "name": "Work" },
                "element.io.section.b": { "tag": "element.io.section.other", "name": "Clash" },
                "m.favourite": { "tag": "m.favourite", "name": "Not a section" },
                "element.io.section.c": "not an object"
            },
            "RoomList.OrderedCustomSections": ["m.favourite", "element.io.section.b", "element.io.section.a"]
        }));
        let found = sections(&settings);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].tag, "element.io.section.a");
    }

    #[test]
    fn a_new_section_goes_above_the_uncategorised_group() {
        let mut settings = settings_from(json!({
            "RoomList.OrderedCustomSections": ["chats", "element.io.section.a"]
        }));
        insert(&mut settings, &section("element.io.section.a", "Work")).unwrap();
        insert(&mut settings, &section("element.io.section.b", "New")).unwrap();

        let order = settings.0.get(ORDERED_SECTIONS).unwrap().as_array().unwrap();
        let tags: Vec<_> = order.iter().filter_map(|v| v.as_str()).collect();
        assert_eq!(tags, ["element.io.section.a", "element.io.section.b", "chats"]);
    }

    #[test]
    fn a_new_section_is_appended_when_no_group_marks_the_end() {
        let mut settings = WebSettings::default();
        insert(&mut settings, &section("element.io.section.a", "Work")).unwrap();
        insert(&mut settings, &section("element.io.section.b", "New")).unwrap();

        let names: Vec<_> = sections(&settings).into_iter().map(|s| s.name).collect();
        assert_eq!(names, ["Work", "New"]);
    }

    #[test]
    fn unrelated_settings_survive_a_section_change() {
        let mut settings = settings_from(json!({
            "RoomList.preferredSorting": "activity",
            "RoomList.CustomSectionData": {
                "element.io.section.a": { "tag": "element.io.section.a", "name": "Work" }
            }
        }));
        rename(&mut settings, "element.io.section.a", "Job").unwrap();
        remove(&mut settings, "element.io.section.a").unwrap();
        assert_eq!(settings.0.get("RoomList.preferredSorting"), Some(&json!("activity")));
    }

    #[test]
    fn removing_a_section_reports_an_unknown_tag() {
        let mut settings = WebSettings::default();
        assert!(remove(&mut settings, "element.io.section.a").is_err());
    }
}