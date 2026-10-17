package io.github.projectcetacean.tamaemu;
import java.util.*;
public final class UiPreferencesTest {
 public static void main(String[] args){
  UiPreferences d=UiPreferences.defaults();
  check(d.size==UiPreferences.Size.BALANCED && d.controls==UiPreferences.Controls.VISIBLE,"default geometry");
  check(d.controlSpace==UiPreferences.ControlSpace.RESERVED && d.placement==UiPreferences.Placement.BOTTOM,"default space/placement");
  check(d.shape==UiPreferences.Shape.CIRCLE && d.shell==UiPreferences.Shell.BLACK && d.buttons==UiPreferences.Buttons.CHARCOAL && d.effect==UiPreferences.Effect.CLEAN && !d.haptics && d.keepAwake && !d.integerPixels,"default appearance/policy");
  check(d.sound,"Sound defaults On");
  sharpnessDefaultsMigrationPersistence(); legacyMapsMigrate(); sizeAndVisibilityIndependent(); newKeysRoundTrip(); resetAppearanceKeepsBehaviour();
  Map<String,String> damaged=new HashMap<>(); damaged.put("size","invalid");damaged.put("shell","PLUM");damaged.put("haptics","maybe");damaged.put("keepAwake","false");damaged.put("unknown","garbage");
  UiPreferences recovered=UiPreferences.decode(damaged);check(recovered.size==UiPreferences.Size.BALANCED && recovered.shell==UiPreferences.Shell.PLUM && !recovered.haptics && !recovered.keepAwake,"independent malformed recovery");
  System.out.println("UiPreferencesTest: defaults, legacyMapsMigrate, sizeAndVisibilityIndependent, newKeysRoundTrip, resetAppearanceKeepsBehaviour passed");
 }
 static void sharpnessDefaultsMigrationPersistence(){
  check("slight".equals(UiPreferences.defaults().encode().get("sharpness")),"LCD defaults to Slight");
  Map<String,String> legacy=new HashMap<>();legacy.put("size","LARGE");legacy.put("effect","LCD_GLASS");legacy.put("sound","false");
  UiPreferences migrated=UiPreferences.decode(legacy);
  check("slight".equals(migrated.encode().get("sharpness")) && migrated.size==UiPreferences.Size.LARGE && migrated.effect==UiPreferences.Effect.LCD_GLASS && !migrated.sound,"absent sharpness migrates independently");
  for(String invalid:new String[]{"unknown","STRONG","",null}){
   legacy.put("sharpness",invalid);check("slight".equals(UiPreferences.decode(legacy).encode().get("sharpness")),"unknown sharpness uses Slight");
  }
  for(String value:new String[]{"off","slight","medium","strong"}){
   UiPreferences p=migrated.with("sharpness",value).with("haptics","true").with("keepAwake","false");
   check(value.equals(UiPreferences.decode(p.encode()).encode().get("sharpness")),"stable lowercase sharpness roundtrip "+value);
   UiPreferences reset=p.resetAppearance();
   check("slight".equals(reset.encode().get("sharpness")) && !reset.sound && reset.haptics && !reset.keepAwake,"appearance reset returns Slight and retains behavior");
  }
 }
 static void legacyMapsMigrate(){
  Map<String,String> m=new HashMap<>();m.put("size","LARGE");m.put("controls","RETRACTABLE");
  UiPreferences p=UiPreferences.decode(m);
  check(p.size==UiPreferences.Size.LARGE && p.controls==UiPreferences.Controls.RETRACTABLE && p.controlSpace==UiPreferences.ControlSpace.OVERLAY && p.placement==UiPreferences.Placement.BOTTOM,"legacyMapsMigrate Large");
  m.put("size","BALANCED");m.put("controls","VISIBLE");p=UiPreferences.decode(m);
  check(p.size==UiPreferences.Size.BALANCED && p.controls==UiPreferences.Controls.VISIBLE && p.controlSpace==UiPreferences.ControlSpace.RESERVED && p.placement==UiPreferences.Placement.BOTTOM,"legacyMapsMigrate Balanced");
  m.put("size","LARGE");m.put("controls","invalid");p=UiPreferences.decode(m);
  check(p.size==UiPreferences.Size.LARGE && p.controls==UiPreferences.defaults().controls && p.controlSpace==UiPreferences.ControlSpace.OVERLAY,"legacyMapsMigrate malformed controls");
  m.remove("controls");check(UiPreferences.decode(m).controls==UiPreferences.Controls.VISIBLE,"legacyMapsMigrate absent controls");
  m.put("controlSpace","bad");m.put("placement","bad");p=UiPreferences.decode(m);
  check(p.controlSpace==UiPreferences.ControlSpace.RESERVED && p.placement==UiPreferences.Placement.BOTTOM,"malformed new keys safe defaults");
 }
 static void sizeAndVisibilityIndependent(){
  UiPreferences d=UiPreferences.defaults().with("controlSpace","OVERLAY").with("placement","RIGHT");
  UiPreferences large=d.withSize(UiPreferences.Size.LARGE).withControls(UiPreferences.Controls.VISIBLE);
  Map<String,String> expected=d.encode();expected.put("size","LARGE");
  check(large.encode().equals(expected),"sizeAndVisibilityIndependent only size key");
  UiPreferences retractable=large.withControls(UiPreferences.Controls.RETRACTABLE);expected.put("controls","RETRACTABLE");
  check(retractable.encode().equals(expected),"sizeAndVisibilityIndependent only controls key");
 }
 static void newKeysRoundTrip(){
  for(UiPreferences.ControlSpace space:UiPreferences.ControlSpace.values())for(UiPreferences.Placement placement:UiPreferences.Placement.values())
   for(UiPreferences.Size size:UiPreferences.Size.values())for(UiPreferences.Effect effect:UiPreferences.Effect.values()){
    UiPreferences p=UiPreferences.defaults().withSize(size).with("controlSpace",space.name()).with("placement",placement.name()).with("effect",effect.name()).with("integerPixels","true").with("shape","ROUNDED_SQUARE").with("haptics","true").with("keepAwake","false");
    UiPreferences decoded=UiPreferences.decode(p.encode());
    check(decoded.controlSpace==space && decoded.placement==placement && p.encode().equals(decoded.encode()),"newKeysRoundTrip all fields");
   }
 }
 static void resetAppearanceKeepsBehaviour(){
  UiPreferences reset=UiPreferences.defaults().withSize(UiPreferences.Size.LARGE).with("controlSpace","OVERLAY").with("placement","TOP").with("effect","LCD_GLASS").with("integerPixels","true").with("haptics","true").with("keepAwake","false").with("sound","false").resetAppearance();
  check(reset.haptics && !reset.keepAwake && !reset.sound && reset.encode().equals(UiPreferences.defaults().with("haptics","true").with("keepAwake","false").with("sound","false").encode()),"resetAppearanceKeepsBehaviour");
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
