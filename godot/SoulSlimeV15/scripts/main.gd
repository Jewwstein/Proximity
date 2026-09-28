extends Node

const WORLD_W := 12.0
const WORLD_H := 6.75
const MOVE_SPEED_N := 0.28
const JOY_RADIUS := 120.0
const ROOM_NAMES := ["AWAKENING HOLLOW", "CRYSTAL DESCENT", "FORKED GALLERY", "SILKEN NEST"]
const ROOM_TEXTURES := [
    "res://assets/rooms/AwakeningHollow.webp",
    "res://assets/rooms/CrystalDescent.webp",
    "res://assets/rooms/ForkedGallery.webp",
    "res://assets/rooms/SilkenNest.webp"
]
const WALK := [
    PackedVector2Array([Vector2(.055,.17),Vector2(.31,.16),Vector2(.43,.29),Vector2(.74,.27),Vector2(.94,.40),Vector2(.94,.82),Vector2(.77,.90),Vector2(.24,.90),Vector2(.07,.68)]),
    PackedVector2Array([Vector2(.42,.10),Vector2(.70,.10),Vector2(.78,.22),Vector2(.67,.37),Vector2(.79,.50),Vector2(.72,.67),Vector2(.58,.90),Vector2(.25,.90),Vector2(.30,.72),Vector2(.44,.55),Vector2(.36,.40),Vector2(.28,.26)]),
    PackedVector2Array([Vector2(.18,.93),Vector2(.82,.93),Vector2(.86,.66),Vector2(.73,.52),Vector2(.91,.35),Vector2(.83,.10),Vector2(.56,.10),Vector2(.50,.34),Vector2(.43,.10),Vector2(.18,.10),Vector2(.10,.36),Vector2(.27,.54),Vector2(.13,.68)]),
    PackedVector2Array([Vector2(.28,.95),Vector2(.72,.95),Vector2(.80,.76),Vector2(.90,.63),Vector2(.91,.37),Vector2(.79,.25),Vector2(.58,.28),Vector2(.48,.36),Vector2(.33,.29),Vector2(.17,.39),Vector2(.12,.62),Vector2(.22,.76)])
]
const ESSENCE_LAYOUT := [
    PackedVector2Array([Vector2(.20,.31),Vector2(.34,.55),Vector2(.49,.70),Vector2(.66,.58),Vector2(.80,.46)]),
    PackedVector2Array([Vector2(.54,.24),Vector2(.57,.43),Vector2(.55,.63),Vector2(.43,.78),Vector2(.61,.82)]),
    PackedVector2Array([Vector2(.43,.73),Vector2(.51,.57),Vector2(.57,.39),Vector2(.66,.27),Vector2(.31,.42)]),
    PackedVector2Array([Vector2(.33,.67),Vector2(.52,.58),Vector2(.72,.48),Vector2(.58,.34),Vector2(.27,.46)])
]
const SLIME_COLORS := [
    Color(0.15,0.72,1.0,0.72), Color(0.55,0.26,1.0,0.72), Color(1.0,0.26,0.12,0.72),
    Color(0.15,1.0,0.44,0.72), Color(0.92,0.96,1.0,0.76), Color(0.18,0.10,0.26,0.78)
]

var screen: Control
var background: TextureRect
var viewport_container: SubViewportContainer
var world_view: SubViewport
var world_root: Node3D
var camera: Camera3D
var player_root: Node3D
var player_visual: Node3D
var enemy_root: Node3D
var enemy_visual: Node3D
var essence_nodes: Array = []

var ui_layer: Control
var room_label: Label
var objective_label: Label
var stats_label: Label
var message_label: Label
var attack_button: Button
var absorb_button: Button
var morph_button: Button

var phase := "death"
var selected_skill := "MAGIC SENSE"
var slime_color_index := 0
var room := 0
var essence_total := 0
var room_essence_taken := [false,false,false,false,false]
var player_n := Vector2(.105,.245)
var enemy_n := Vector2(.70,.55)
var enemy_dir := 1.0
var player_hp := 100
var mite_hp := 5
var spider_hp := 8
var mite_defeated := false
var spider_defeated := false
var mite_absorbed := false
var spider_absorbed := false
var form := "Slime"
var facing_yaw := 0.0
var transition_cooldown := 0.0
var enemy_attack_cooldown := 1.4

var left_finger := -1
var look_finger := -1
var joy_origin := Vector2.ZERO
var joy_now := Vector2.ZERO
var joy_vector := Vector2.ZERO
var look_last := Vector2.ZERO
var joy_base: ColorRect
var joy_knob: ColorRect

func _ready() -> void:
    DisplayServer.window_set_mode(DisplayServer.WINDOW_MODE_FULLSCREEN)
    DisplayServer.screen_set_orientation(DisplayServer.SCREEN_SENSOR)
    Engine.max_fps = 60
    build_screen()
    build_world()
    show_death()

func build_screen() -> void:
    screen = Control.new()
    screen.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    add_child(screen)

    background = TextureRect.new()
    background.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    background.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
    background.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
    background.mouse_filter = Control.MOUSE_FILTER_IGNORE
    screen.add_child(background)

    var shade := ColorRect.new()
    shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    shade.color = Color(0.01,0.02,0.035,0.10)
    shade.mouse_filter = Control.MOUSE_FILTER_IGNORE
    screen.add_child(shade)

    viewport_container = SubViewportContainer.new()
    viewport_container.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    viewport_container.stretch = true
    viewport_container.mouse_filter = Control.MOUSE_FILTER_IGNORE
    screen.add_child(viewport_container)

    world_view = SubViewport.new()
    world_view.size = Vector2i(1280,720)
    world_view.transparent_bg = true
    world_view.render_target_update_mode = SubViewport.UPDATE_ALWAYS
    viewport_container.add_child(world_view)

    ui_layer = Control.new()
    ui_layer.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    screen.add_child(ui_layer)

func build_world() -> void:
    world_root = Node3D.new()
    world_root.name = "World3D"
    world_view.add_child(world_root)

    var env_node := WorldEnvironment.new()
    var env := Environment.new()
    env.background_mode = Environment.BG_COLOR
    env.background_color = Color(0,0,0,0)
    env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
    env.ambient_light_color = Color(0.44,0.52,0.70)
    env.ambient_light_energy = 1.05
    env_node.environment = env
    world_root.add_child(env_node)

    camera = Camera3D.new()
    camera.projection = Camera3D.PROJECTION_ORTHOGONAL
    camera.size = WORLD_H
    camera.position = Vector3(0,0,10)
    camera.current = true
    world_root.add_child(camera)

    var key := DirectionalLight3D.new()
    key.light_color = Color(0.72,0.84,1.0)
    key.light_energy = 1.6
    key.rotation_degrees = Vector3(-24,-32,0)
    key.shadow_enabled = true
    world_root.add_child(key)

    var rim := DirectionalLight3D.new()
    rim.light_color = Color(0.36,0.70,1.0)
    rim.light_energy = 0.7
    rim.rotation_degrees = Vector3(25,145,0)
    world_root.add_child(rim)

func clear_ui() -> void:
    for child in ui_layer.get_children():
        child.queue_free()
    room_label = null
    objective_label = null
    stats_label = null
    message_label = null
    attack_button = null
    absorb_button = null
    morph_button = null
    joy_base = null
    joy_knob = null
    left_finger = -1
    look_finger = -1
    joy_vector = Vector2.ZERO

func clear_actors() -> void:
    if is_instance_valid(player_root):
        player_root.queue_free()
    if is_instance_valid(enemy_root):
        enemy_root.queue_free()
    player_root = null
    player_visual = null
    enemy_root = null
    enemy_visual = null
    for entry in essence_nodes:
        if is_instance_valid(entry.get("node")):
            entry["node"].queue_free()
    essence_nodes.clear()

func show_death() -> void:
    phase = "death"
    clear_ui()
    clear_actors()
    background.texture = null
    background.modulate = Color(1,1,1,1)
    var panel := make_panel(Vector2(.18,.17),Vector2(.82,.83))
    add_label(panel,"FINAL MOMENTS",32,Color(0.60,0.92,1),Vector2(.08,.72),Vector2(.92,.90),HORIZONTAL_ALIGNMENT_CENTER)
    add_label(panel,"A LIFE ENDS",50,Color.WHITE,Vector2(.08,.52),Vector2(.92,.72),HORIZONTAL_ALIGNMENT_CENTER)
    add_label(panel,"A blinding instant ends your former life. The world disappears. Something else is listening.",20,Color(0.76,0.86,0.92),Vector2(.12,.30),Vector2(.88,.52),HORIZONTAL_ALIGNMENT_CENTER)
    var b := make_button(panel,"ACCEPT FATE  •  TAP TO CONTINUE",Vector2(.19,.09),Vector2(.81,.25))
    b.pressed.connect(show_rebirth)

func show_rebirth() -> void:
    phase = "rebirth"
    clear_ui()
    var panel := make_panel(Vector2(.12,.14),Vector2(.88,.86))
    add_label(panel,"SOUL ANALYSIS // CONSCIOUSNESS DETECTED",18,Color(0.42,0.92,1),Vector2(.05,.80),Vector2(.95,.94),HORIZONTAL_ALIGNMENT_CENTER)
    add_label(panel,"REINCARNATION PARAMETERS",34,Color.WHITE,Vector2(.05,.65),Vector2(.95,.82),HORIZONTAL_ALIGNMENT_CENTER)
    add_label(panel,"Choose one starting ability before your new vessel is formed.",19,Color(0.76,0.88,0.94),Vector2(.08,.53),Vector2(.92,.66),HORIZONTAL_ALIGNMENT_CENTER)
    var skills := ["MAGIC SENSE","ACCELERATED THOUGHT","REGENERATION"]
    for i in range(skills.size()):
        var bx := .07 + i*.31
        var b := make_button(panel,skills[i],Vector2(bx,.28),Vector2(bx+.27,.48))
        b.pressed.connect(_choose_skill.bind(skills[i]))
    add_label(panel,"Tap a skill to continue.",15,Color(0.55,0.75,0.82),Vector2(.10,.10),Vector2(.90,.22),HORIZONTAL_ALIGNMENT_CENTER)

func _choose_skill(skill: String) -> void:
    selected_skill = skill
    show_creator()

func show_creator() -> void:
    phase = "creator"
    clear_ui()
    clear_actors()
    background.texture = null
    spawn_player()
    player_root.position = Vector3(0,-.15,0)
    player_root.scale = Vector3.ONE * 1.55
    add_label(ui_layer,"VESSEL CREATOR",32,Color.WHITE,Vector2(.30,.03),Vector2(.70,.12),HORIZONTAL_ALIGNMENT_CENTER)
    add_label(ui_layer,"Drag the right half of the screen to rotate your 3D vessel.",16,Color(0.55,0.82,0.92),Vector2(.28,.12),Vector2(.72,.18),HORIZONTAL_ALIGNMENT_CENTER)
    add_label(ui_layer,"COLOR",17,Color(0.45,0.92,1),Vector2(.04,.20),Vector2(.22,.27),HORIZONTAL_ALIGNMENT_CENTER)
    var names := ["AZURE","AMETHYST","EMBER","JADE","PEARL","SHADOW"]
    for i in range(names.size()):
        var y0 := .28 + i*.075
        var b := make_button(ui_layer,names[i],Vector2(.04,y0),Vector2(.22,y0+.06))
        b.pressed.connect(_choose_color.bind(i))
    add_label(ui_layer,"Starting Skill\n"+selected_skill+"\n\nOriginal FBX creatures are now rendered directly by the engine.",17,Color(0.78,0.90,0.95),Vector2(.76,.28),Vector2(.97,.62),HORIZONTAL_ALIGNMENT_CENTER)
    var start := make_button(ui_layer,"ENTER THE CRYSTAL CAVE",Vector2(.73,.71),Vector2(.97,.83))
    start.pressed.connect(start_cave)

func _choose_color(index: int) -> void:
    slime_color_index = index
    set_player_form("Slime")

func start_cave() -> void:
    phase = "cave"
    clear_ui()
    clear_actors()
    room = 0
    essence_total = 0
    player_hp = 100
    mite_hp = 5
    spider_hp = 8
    mite_defeated = false
    spider_defeated = false
    mite_absorbed = false
    spider_absorbed = false
    form = "Slime"
    build_cave_ui()
    spawn_room(default_spawn(0))
    set_message("Awakening stabilized. Collect the nearby mana essence.")

func build_cave_ui() -> void:
    var top := make_panel(Vector2(.015,.015),Vector2(.985,.19))
    room_label = add_label(top,"",22,Color(0.55,0.94,1),Vector2(.02,.08),Vector2(.30,.44),HORIZONTAL_ALIGNMENT_LEFT)
    objective_label = add_label(top,"",15,Color(0.86,0.94,0.97),Vector2(.29,.08),Vector2(.75,.48),HORIZONTAL_ALIGNMENT_LEFT)
    stats_label = add_label(top,"",15,Color.WHITE,Vector2(.75,.08),Vector2(.98,.48),HORIZONTAL_ALIGNMENT_RIGHT)
    message_label = add_label(top,"",14,Color(0.62,0.84,0.92),Vector2(.04,.52),Vector2(.96,.92),HORIZONTAL_ALIGNMENT_CENTER)

    joy_base = ColorRect.new()
    joy_base.color = Color(0.05,0.18,0.25,0.28)
    place(joy_base,Vector2(.035,.69),Vector2(.20,.96))
    joy_base.mouse_filter = Control.MOUSE_FILTER_IGNORE
    ui_layer.add_child(joy_base)
    joy_knob = ColorRect.new()
    joy_knob.color = Color(0.28,0.84,1.0,0.55)
    joy_knob.size = Vector2(54,54)
    joy_knob.position = joy_base.size*.5 - joy_knob.size*.5
    joy_knob.mouse_filter = Control.MOUSE_FILTER_IGNORE
    joy_base.add_child(joy_knob)

    attack_button = make_button(ui_layer,"MAGIC\nBURST",Vector2(.68,.81),Vector2(.78,.965))
    absorb_button = make_button(ui_layer,"ABSORB",Vector2(.79,.81),Vector2(.89,.965))
    morph_button = make_button(ui_layer,"MORPH",Vector2(.90,.81),Vector2(.985,.965))
    attack_button.pressed.connect(player_attack)
    absorb_button.pressed.connect(absorb_enemy)
    morph_button.pressed.connect(cycle_morph)

func spawn_room(spawn: Vector2) -> void:
    if is_instance_valid(enemy_root):
        enemy_root.queue_free()
    enemy_root = null
    enemy_visual = null
    for entry in essence_nodes:
        if is_instance_valid(entry.get("node")):
            entry["node"].queue_free()
    essence_nodes.clear()
    room_essence_taken = [false,false,false,false,false]
    var tex = load(ROOM_TEXTURES[room])
    if tex is Texture2D:
        background.texture = tex
    if not is_instance_valid(player_root):
        spawn_player()
    player_n = spawn
    spawn_enemy()
    spawn_essence()
    transition_cooldown = .65
    update_actor_transforms()
    refresh_ui()

func default_spawn(r: int) -> Vector2:
    if r == 0: return Vector2(.105,.245)
    if r == 1: return Vector2(.53,.17)
    if r == 2: return Vector2(.50,.82)
    return Vector2(.49,.83)

func spawn_player() -> void:
    player_root = Node3D.new()
    player_root.name = "Player"
    world_root.add_child(player_root)
    set_player_form(form)

func set_player_form(new_form: String) -> void:
    form = new_form
    if not is_instance_valid(player_root): return
    if is_instance_valid(player_visual):
        player_visual.queue_free()
    if form == "Razorbeast":
        player_visual = instantiate_creature("Razorbeast",1.22)
    elif form == "Voidweaver":
        player_visual = instantiate_creature("Voidweaver",1.34)
    else:
        player_visual = create_slime()
    player_root.add_child(player_visual)
    player_visual.rotation_degrees.y = facing_yaw

func create_slime() -> Node3D:
    var root := Node3D.new()
    root.name = "SlimeVisual"
    var body := MeshInstance3D.new()
    var sphere := SphereMesh.new()
    sphere.radius = .52
    sphere.height = .86
    var mat := StandardMaterial3D.new()
    mat.albedo_color = SLIME_COLORS[slime_color_index]
    mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
    mat.roughness = .26
    mat.metallic = .04
    mat.emission_enabled = true
    mat.emission = Color(SLIME_COLORS[slime_color_index].r*.22,SLIME_COLORS[slime_color_index].g*.22,SLIME_COLORS[slime_color_index].b*.30)
    mat.emission_energy_multiplier = 1.4
    sphere.material = mat
    body.mesh = sphere
    body.scale = Vector3(1.08,.82,1.0)
    root.add_child(body)
    var core := MeshInstance3D.new()
    var core_mesh := SphereMesh.new()
    core_mesh.radius = .15
    core_mesh.height = .28
    var core_mat := StandardMaterial3D.new()
    core_mat.albedo_color = Color(0.35,0.92,1)
    core_mat.emission_enabled = true
    core_mat.emission = Color(0.08,0.72,1)
    core_mat.emission_energy_multiplier = 4.0
    core_mesh.material = core_mat
    core.mesh = core_mesh
    core.position = Vector3(0,-.03,.30)
    root.add_child(core)
    for sx in [-1.0,1.0]:
        var eye := MeshInstance3D.new()
        var em := SphereMesh.new()
        em.radius = .065
        em.height = .12
        var eye_mat := StandardMaterial3D.new()
        eye_mat.albedo_color = Color(0.92,0.98,1)
        em.material = eye_mat
        eye.mesh = em
        eye.position = Vector3(.17*sx,.12,.47)
        root.add_child(eye)
        var pupil := MeshInstance3D.new()
        var pm := SphereMesh.new()
        pm.radius = .028
        pm.height = .05
        var pmat := StandardMaterial3D.new()
        pmat.albedo_color = Color(0.02,0.05,0.08)
        pm.material = pmat
        pupil.mesh = pm
        pupil.position = Vector3(.17*sx,.12,.525)
        root.add_child(pupil)
    return root

func instantiate_creature(kind: String, target_height: float) -> Node3D:
    var holder := Node3D.new()
    holder.name = kind+"Visual"
    var path := "res://assets/creatures/"+kind+"/"+kind+".fbx"
    var packed = load(path)
    if packed is PackedScene:
        var model := packed.instantiate()
        holder.add_child(model)
        apply_creature_material(model,kind)
        normalize_model(model,target_height)
        model.rotation_degrees.y = 180.0
    else:
        var fallback := MeshInstance3D.new()
        var fallback_mesh := SphereMesh.new()
        fallback_mesh.radius = .55
        fallback_mesh.height = target_height
        var fallback_mat := StandardMaterial3D.new()
        fallback_mat.albedo_color = Color(0.5,0.12,0.16) if kind == "Razorbeast" else Color(0.12,0.22,0.38)
        fallback_mesh.material = fallback_mat
        fallback.mesh = fallback_mesh
        holder.add_child(fallback)
    return holder

func apply_creature_material(node: Node, kind: String) -> void:
    var base_path := "res://assets/creatures/"+kind+"/"+kind
    var albedo = load(base_path+"_BaseColor.png")
    var normal = load(base_path+"_Normal.png")
    var rough = load(base_path+"_Roughness.png")
    var metal = load(base_path+"_Metallic.png")
    for child in find_meshes(node):
        var mat := StandardMaterial3D.new()
        if albedo is Texture2D: mat.albedo_texture = albedo
        if normal is Texture2D:
            mat.normal_enabled = true
            mat.normal_texture = normal
        if rough is Texture2D: mat.roughness_texture = rough
        else: mat.roughness = .68
        if metal is Texture2D: mat.metallic_texture = metal
        else: mat.metallic = .08
        child.material_override = mat

func find_meshes(root: Node) -> Array:
    var out: Array = []
    if root is MeshInstance3D:
        out.append(root)
    for child in root.get_children():
        out.append_array(find_meshes(child))
    return out

func normalize_model(model: Node3D, target_height: float) -> void:
    var meshes := find_meshes(model)
    if meshes.is_empty(): return
    var min_v := Vector3(1e20,1e20,1e20)
    var max_v := Vector3(-1e20,-1e20,-1e20)
    for m in meshes:
        if m.mesh == null: continue
        var aabb: AABB = m.get_aabb()
        var rel: Transform3D = model.global_transform.affine_inverse() * m.global_transform
        for x in [0.0,1.0]:
            for y in [0.0,1.0]:
                for z in [0.0,1.0]:
                    var p := aabb.position + Vector3(aabb.size.x*x,aabb.size.y*y,aabb.size.z*z)
                    p = rel * p
                    min_v = min_v.min(p)
                    max_v = max_v.max(p)
    var size := max_v-min_v
    if size.y <= .0001: return
    var scale_factor := target_height/size.y
    var center := (min_v+max_v)*.5
    model.scale = Vector3.ONE*scale_factor
    model.position = -center*scale_factor

func spawn_enemy() -> void:
    if room != 0 and room != 3: return
    if room == 0 and mite_absorbed: return
    if room == 3 and spider_absorbed: return
    enemy_root = Node3D.new()
    world_root.add_child(enemy_root)
    if room == 0:
        enemy_n = Vector2(.70,.55)
        enemy_visual = instantiate_creature("Razorbeast",1.15)
        enemy_dir = 1.0
    else:
        enemy_n = Vector2(.69,.40)
        enemy_visual = instantiate_creature("Voidweaver",1.48)
        enemy_dir = -1.0
    enemy_root.add_child(enemy_visual)
    var light := OmniLight3D.new()
    light.light_color = Color(1.0,.30,.12) if room == 0 else Color(.35,.30,1.0)
    light.light_energy = 1.4
    light.omni_range = 2.8
    light.position = Vector3(0,.3,1.0)
    enemy_root.add_child(light)

func spawn_essence() -> void:
    for i in range(ESSENCE_LAYOUT[room].size()):
        var n: Vector2 = ESSENCE_LAYOUT[room][i]
        var root := Node3D.new()
        root.position = norm_to_world(n,.16)
        var orb := MeshInstance3D.new()
        var mesh := SphereMesh.new()
        mesh.radius = .09
        mesh.height = .18
        var mat := StandardMaterial3D.new()
        mat.albedo_color = Color(.12,.72,1.0)
        mat.emission_enabled = true
        mat.emission = Color(.04,.55,1.0)
        mat.emission_energy_multiplier = 4.0
        mesh.material = mat
        orb.mesh = mesh
        root.add_child(orb)
        var light := OmniLight3D.new()
        light.light_color = Color(.12,.60,1.0)
        light.light_energy = .65
        light.omni_range = 1.2
        root.add_child(light)
        world_root.add_child(root)
        essence_nodes.append({"node":root,"n":n,"taken":false,"orb":orb})

func _process(delta: float) -> void:
    if phase == "creator":
        if is_instance_valid(player_visual):
            player_visual.rotation_degrees.y = facing_yaw
        return
    if phase != "cave": return
    transition_cooldown = max(0.0,transition_cooldown-delta)
    update_player(delta)
    update_enemy(delta)
    update_actor_transforms()
    collect_essence()
    handle_transitions()

func update_player(delta: float) -> void:
    if joy_vector.length() > .04:
        var candidate := player_n + joy_vector*MOVE_SPEED_N*delta
        player_n = clamp_to_polygon(candidate,WALK[room])
    if is_instance_valid(player_visual):
        player_visual.rotation_degrees.y = facing_yaw

func update_enemy(delta: float) -> void:
    if not is_enemy_alive(): return
    var speed := .035 if room == 0 else .022
    enemy_n.x += enemy_dir*speed*delta
    var min_x := .57 if room == 0 else .58
    var max_x := .80 if room == 0 else .77
    if enemy_n.x > max_x:
        enemy_n.x = max_x
        enemy_dir = -1.0
    elif enemy_n.x < min_x:
        enemy_n.x = min_x
        enemy_dir = 1.0
    if is_instance_valid(enemy_visual):
        enemy_visual.rotation_degrees.y = 180.0 if enemy_dir < 0 else 0.0
        enemy_visual.position.y = sin(Time.get_ticks_msec()*.003)*.035
    enemy_attack_cooldown -= delta
    if enemy_attack_cooldown <= 0.0 and player_n.distance_to(enemy_n) < .30:
        enemy_attack_cooldown = 2.5 if room == 0 else 2.9
        var damage := 7 if room == 0 else 12
        player_hp = max(0,player_hp-damage)
        spawn_flash(norm_to_world(player_n,.2),Color(1,.18,.08),.28)
        set_message(("Razorbeast" if room == 0 else "Voidweaver")+" strikes for "+str(damage)+" damage.")
        if player_hp <= 0:
            player_hp = 100
            player_n = default_spawn(room)
            set_message("Your slime body reforms at the room entrance.")
        refresh_ui()

func update_actor_transforms() -> void:
    if is_instance_valid(player_root): player_root.position = norm_to_world(player_n,.22)
    if is_instance_valid(enemy_root): enemy_root.position = norm_to_world(enemy_n,.20)

func collect_essence() -> void:
    for entry in essence_nodes:
        if entry["taken"]: continue
        if player_n.distance_to(entry["n"]) < .055:
            entry["taken"] = true
            essence_total += 1
            if is_instance_valid(entry["node"]): entry["node"].visible = false
            spawn_flash(norm_to_world(entry["n"],.2),Color(.15,.75,1),.22)
            set_message("Mana essence absorbed. Total essence: "+str(essence_total)+".")
            refresh_ui()

func handle_transitions() -> void:
    if transition_cooldown > 0.0: return
    if room == 0 and player_n.x > .915 and player_n.y > .35:
        if essence_total >= 5 and mite_absorbed:
            room = 1
            spawn_room(Vector2(.53,.17))
            set_message("The sealed passage opens into the Crystal Descent.")
        else:
            player_n.x = .89
            set_message("The passage remains sealed: collect five essence and absorb the Razorbeast.")
    elif room == 1:
        if player_n.y > .885:
            room = 2
            spawn_room(Vector2(.50,.82))
            set_message("You enter the Forked Gallery.")
        elif player_n.y < .115:
            room = 0
            spawn_room(Vector2(.87,.48))
    elif room == 2:
        if player_n.y < .115 and player_n.x > .42:
            room = 3
            spawn_room(Vector2(.49,.83))
            set_message("Silken Nest. Something armored moves in the webbing.")
        elif player_n.y > .915:
            room = 1
            spawn_room(Vector2(.50,.82))
    elif room == 3 and player_n.y > .935:
        room = 2
        spawn_room(Vector2(.55,.18))

func player_attack() -> void:
    if not is_enemy_alive():
        set_message("No active target.")
        return
    if player_n.distance_to(enemy_n) > .34:
        set_message("Move closer before attacking.")
        return
    var damage := 1
    if form == "Razorbeast": damage = 2
    elif form == "Voidweaver": damage = 3
    if room == 0:
        mite_hp = max(0,mite_hp-damage)
        if mite_hp == 0:
            mite_defeated = true
            set_message("Molten Razorbeast defeated. Move close and ABSORB it.")
        else:
            set_message("Magic burst hits the Razorbeast for "+str(damage)+".")
    else:
        spider_hp = max(0,spider_hp-damage)
        if spider_hp == 0:
            spider_defeated = true
            set_message("Armored Voidweaver defeated. Move close and ABSORB it.")
        else:
            set_message("Magic burst cracks against the Voidweaver for "+str(damage)+".")
    spawn_flash(norm_to_world(enemy_n,.2),Color(.22,.86,1),.34)
    refresh_ui()

func absorb_enemy() -> void:
    if not is_enemy_defeated():
        set_message("Defeat the creature before absorbing it.")
        return
    if player_n.distance_to(enemy_n) > .31:
        set_message("Move closer to the defeated creature before absorbing it.")
        return
    spawn_flash(norm_to_world(enemy_n,.2),Color(.62,.24,1),.55)
    if room == 0:
        mite_absorbed = true
        mite_defeated = false
        form = "Razorbeast"
        set_player_form(form)
        set_message("Razorbeast absorbed. RAZORBEAST MORPH unlocked. The eastern path is open.")
    else:
        spider_absorbed = true
        spider_defeated = false
        form = "Voidweaver"
        set_player_form(form)
        set_message("VOIDWEAVER MORPH unlocked. The v10 vertical slice is complete.")
    if is_instance_valid(enemy_root):
        enemy_root.queue_free()
        enemy_root = null
    refresh_ui()

func cycle_morph() -> void:
    var forms := ["Slime"]
    if mite_absorbed: forms.append("Razorbeast")
    if spider_absorbed: forms.append("Voidweaver")
    if forms.size() <= 1:
        set_message("Absorb a creature before morphing.")
        return
    var idx := forms.find(form)
    idx = (idx+1)%forms.size()
    set_player_form(forms[idx])
    set_message("Morph changed to "+form+".")
    refresh_ui()

func is_enemy_alive() -> bool:
    if room == 0: return not mite_defeated and not mite_absorbed
    if room == 3: return not spider_defeated and not spider_absorbed
    return false

func is_enemy_defeated() -> bool:
    if room == 0: return mite_defeated and not mite_absorbed
    if room == 3: return spider_defeated and not spider_absorbed
    return false

func refresh_ui() -> void:
    if room_label == null: return
    room_label.text = "CRYSTAL CAVE  //  "+ROOM_NAMES[room]
    var objective := ""
    if room == 0:
        if essence_total < 5: objective = "Collect the five mana essence points."
        elif not mite_defeated and not mite_absorbed: objective = "Approach and defeat the Molten Razorbeast."
        elif mite_defeated: objective = "Absorb the defeated Razorbeast."
        else: objective = "The path east is open. Enter the Crystal Descent."
    elif room == 1: objective = "Descend through the crystal passage."
    elif room == 2: objective = "Take the upper-right path toward the Silken Nest."
    elif not spider_defeated and not spider_absorbed: objective = "Defeat the Armored Voidweaver."
    elif spider_defeated: objective = "Absorb the Armored Voidweaver."
    else: objective = "Voidweaver Morph unlocked. The v10 vertical slice is complete."
    objective_label.text = objective
    stats_label.text = "HP "+str(player_hp)+"   ESSENCE "+str(essence_total)+"\nFORM  "+form
    if attack_button != null: attack_button.disabled = not is_enemy_alive()
    if absorb_button != null: absorb_button.disabled = not is_enemy_defeated()
    if morph_button != null: morph_button.disabled = not mite_absorbed and not spider_absorbed

func set_message(text: String) -> void:
    if message_label != null: message_label.text = text

func norm_to_world(n: Vector2, z: float = 0.0) -> Vector3:
    return Vector3((n.x-.5)*WORLD_W,(.5-n.y)*WORLD_H,z)

func clamp_to_polygon(point: Vector2, poly: PackedVector2Array) -> Vector2:
    if Geometry2D.is_point_in_polygon(point,poly): return point
    var best := poly[0]
    var best_d := 1e20
    for i in range(poly.size()):
        var a := poly[i]
        var b := poly[(i+1)%poly.size()]
        var c := Geometry2D.get_closest_point_to_segment(point,a,b)
        var d := point.distance_squared_to(c)
        if d < best_d:
            best_d = d
            best = c
    return best

func spawn_flash(pos: Vector3, color: Color, final_scale: float) -> void:
    var flash := MeshInstance3D.new()
    var sm := SphereMesh.new()
    sm.radius = .12
    sm.height = .22
    var mat := StandardMaterial3D.new()
    mat.albedo_color = Color(color.r,color.g,color.b,.72)
    mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
    mat.emission_enabled = true
    mat.emission = color
    mat.emission_energy_multiplier = 5.0
    sm.material = mat
    flash.mesh = sm
    flash.position = pos+Vector3(0,0,.8)
    flash.scale = Vector3.ONE*.25
    world_root.add_child(flash)
    var tw := create_tween()
    tw.tween_property(flash,"scale",Vector3.ONE*final_scale/.12,.28).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
    tw.tween_callback(Callable(flash,"queue_free"))

func _input(event: InputEvent) -> void:
    if phase != "cave" and phase != "creator": return
    var vsize := get_viewport().get_visible_rect().size
    if event is InputEventScreenTouch:
        var e := event as InputEventScreenTouch
        if e.pressed:
            if phase == "cave" and e.position.x < vsize.x*.43 and e.position.y > vsize.y*.24:
                if left_finger == -1:
                    left_finger = e.index
                    joy_origin = e.position
                    joy_now = e.position
                    update_joystick_visual(vsize)
            elif e.position.x > vsize.x*.50 and not (phase == "cave" and e.position.y > vsize.y*.78 and e.position.x > vsize.x*.62):
                if look_finger == -1:
                    look_finger = e.index
                    look_last = e.position
        else:
            if e.index == left_finger:
                left_finger = -1
                joy_vector = Vector2.ZERO
                reset_joystick_visual()
            if e.index == look_finger: look_finger = -1
    elif event is InputEventScreenDrag:
        var d := event as InputEventScreenDrag
        if d.index == left_finger and phase == "cave":
            joy_now = d.position
            var delta_vec := joy_now-joy_origin
            joy_vector = delta_vec/JOY_RADIUS
            if joy_vector.length() > 1.0: joy_vector = joy_vector.normalized()
            update_joystick_visual(vsize)
        elif d.index == look_finger:
            var dx := d.position.x-look_last.x
            facing_yaw += dx*.32
            look_last = d.position
    elif event is InputEventMouseMotion and Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT):
        facing_yaw += (event as InputEventMouseMotion).relative.x*.28

func update_joystick_visual(_vsize: Vector2) -> void:
    if joy_base == null or joy_knob == null: return
    var delta_vec := joy_now-joy_origin
    if delta_vec.length() > JOY_RADIUS: delta_vec = delta_vec.normalized()*JOY_RADIUS
    var scale := joy_base.size.x/(JOY_RADIUS*2.2)
    joy_knob.position = joy_base.size*.5-joy_knob.size*.5+delta_vec*scale

func reset_joystick_visual() -> void:
    if joy_base != null and joy_knob != null:
        joy_knob.position = joy_base.size*.5-joy_knob.size*.5

func make_panel(a0: Vector2, a1: Vector2) -> Panel:
    var p := Panel.new()
    place(p,a0,a1)
    var sb := StyleBoxFlat.new()
    sb.bg_color = Color(.015,.045,.075,.90)
    sb.border_color = Color(.16,.62,.80,.72)
    sb.set_border_width_all(2)
    sb.corner_radius_top_left = 14
    sb.corner_radius_top_right = 14
    sb.corner_radius_bottom_left = 14
    sb.corner_radius_bottom_right = 14
    p.add_theme_stylebox_override("panel",sb)
    ui_layer.add_child(p)
    return p

func make_button(parent: Control, text: String, a0: Vector2, a1: Vector2) -> Button:
    var b := Button.new()
    b.text = text
    b.add_theme_font_size_override("font_size",16)
    var normal := StyleBoxFlat.new()
    normal.bg_color = Color(.025,.12,.18,.94)
    normal.border_color = Color(.15,.70,.88,.85)
    normal.set_border_width_all(2)
    normal.corner_radius_top_left = 12
    normal.corner_radius_top_right = 12
    normal.corner_radius_bottom_left = 12
    normal.corner_radius_bottom_right = 12
    b.add_theme_stylebox_override("normal",normal)
    var pressed := normal.duplicate()
    pressed.bg_color = Color(.08,.35,.48,.98)
    b.add_theme_stylebox_override("pressed",pressed)
    b.add_theme_stylebox_override("hover",pressed)
    place(b,a0,a1)
    parent.add_child(b)
    return b

func add_label(parent: Control, text: String, size: int, color: Color, a0: Vector2, a1: Vector2, align: HorizontalAlignment) -> Label:
    var l := Label.new()
    l.text = text
    l.add_theme_font_size_override("font_size",size)
    l.add_theme_color_override("font_color",color)
    l.horizontal_alignment = align
    l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    place(l,a0,a1)
    parent.add_child(l)
    return l

func place(c: Control, a0: Vector2, a1: Vector2) -> void:
    c.anchor_left = a0.x
    c.anchor_top = a0.y
    c.anchor_right = a1.x
    c.anchor_bottom = a1.y
    c.offset_left = 0
    c.offset_top = 0
    c.offset_right = 0
    c.offset_bottom = 0