package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.HexMath
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.tile.ResourceType
import com.unciv.models.gamebasics.tile.TerrainType
import com.unciv.models.gamebasics.tile.TileResource
import com.unciv.ui.utils.getRandom
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin

enum class MapType {
    Perlin,
    Default,
    Pangaea,
}

class CelluarAutomataRandomMapGenerator(): SeedRandomMapGenerator() {
    var landProb = 0.55f
    var numSmooth = 4
    var mapType = MapType.Default

    constructor(type: MapType): this() {
        mapType = type
        if (mapType < MapType.Default) {
            mapType = MapType.Default
        }
    }

    override fun generateMap(distance: Int): HashMap<String, TileInfo> {
        val mapVectors = HexMath().getVectorsInDistance(Vector2.Zero, distance)
        val landscape = HashMap<Vector2, TerrainType>()

        //init
        for (vector in mapVectors) {
            landscape[vector] = generateInitTerrain(vector, distance)
        }

        //smooth
        for (loop in 0..numSmooth) {
            for (vector in mapVectors) {
                if (HexMath().getDistance(Vector2.Zero, vector) < distance) {
                    val neighborLands = HexMath().getAdjacentVectors(vector).count {landscape[it] == TerrainType.Land}
                    if (landscape[vector] == TerrainType.Land) {
                        if (neighborLands < 3)
                            landscape[vector] = TerrainType.Water
                    } else {
                        if (neighborLands > 3)
                            landscape[vector] = TerrainType.Land
                    }
                }
                else {
                    landscape[vector] = TerrainType.Water
                }
            }
        }

        val map = HashMap<Vector2, TileInfo>()
        for (vector in mapVectors)
            map[vector] = generateTile(vector,landscape[vector]!!)

        divideIntoAreas2(6, 0.05f, distance, map)

        val mapToReturn = HashMap<String, TileInfo>()
        for(tile in map) {
            tile.value.setTransients()
            mapToReturn[tile.key.toString()] = tile.value
        }

        setWaterTiles(mapToReturn)

        for(tile in mapToReturn.values) randomizeTile(tile,mapToReturn)

        return mapToReturn
    }

    private fun generateInitTerrain(vector: Vector2, distance: Int): TerrainType {
        val type: TerrainType
        if (mapType == MapType.Pangaea) {
            val distanceFactor = (HexMath().getDistance(Vector2.Zero, vector) * 1.8 / distance).toFloat()
            type = if (Random().nextDouble() < landProb.pow(distanceFactor)) TerrainType.Land else TerrainType.Water
        } else { //default
            if (HexMath().getDistance(Vector2.Zero, vector) > 0.9f * distance)
                type = if (Random().nextDouble() < 0.1) TerrainType.Land else TerrainType.Water
            else if (HexMath().getDistance(Vector2.Zero, vector) > 0.85f * distance)
                type = if (Random().nextDouble() < 0.2) TerrainType.Land else TerrainType.Water
            else
                type = if (Random().nextDouble() < landProb) TerrainType.Land else TerrainType.Water
        }
        return type
    }

    private fun generateTile(vector: Vector2, type: TerrainType): TileInfo {
        val tile=TileInfo()
        tile.position=vector
        if (type == TerrainType.Land) tile.baseTerrain = ""
        else tile.baseTerrain = "Ocean"
        return tile
    }

    override fun setWaterTiles(map: HashMap<String, TileInfo>) {
        //define lakes
        var waterTiles = map.values.filter { it.isWater() }.map { it.position }
        val tilesInArea = ArrayList<Vector2>()
        val tilesToCheck = ArrayList<Vector2>()
        while (waterTiles.isNotEmpty()) {
            val initialWaterTile = waterTiles.getRandom()
            tilesInArea += initialWaterTile
            tilesToCheck += initialWaterTile
            waterTiles -= initialWaterTile

            while (tilesToCheck.isNotEmpty()) {
                val tileChecking = tilesToCheck.getRandom()
                for (vector in HexMath().getVectorsAtDistance(tileChecking,1)
                        .filter { !tilesInArea.contains(it) and waterTiles.contains(it) }) {
                    tilesInArea += vector
                    tilesToCheck += vector
                    waterTiles -= vector
                }
                tilesToCheck -= tileChecking
            }

            if (tilesInArea.size <= 10) {
                for (vector in tilesInArea) {
                    val tile = map[vector.toString()]!!
                    tile.baseTerrain = "Lakes"
                    tile.setTransients()
                }
            }
            tilesInArea.clear()
        }

        //Coasts
        for (tile in map.values.filter { it.baseTerrain == "Ocean" }) {
            if (HexMath().getVectorsInDistance(tile.position,2).any { hasLandTile(map,it) }) {
                tile.baseTerrain = "Coast"
                tile.setTransients()
            }
        }
    }

    override fun randomizeTile(tileInfo: TileInfo, map: HashMap<String, TileInfo>){
        if(tileInfo.getBaseTerrain().type==TerrainType.Land && Math.random()<0.05f){
            tileInfo.baseTerrain = "Mountain"
            tileInfo.setTransients()
        }
        addRandomTerrainFeature(tileInfo)
        addRandomResourceToTile(tileInfo)
        maybeAddAncientRuins(tileInfo)
    }

    fun getLatitude(vector: Vector2): Float {
        return (sin(3.1416/3) * vector.y).toFloat()
    }

    fun divideIntoAreas2(averageTilesPerArea: Int, waterPercent: Float, distance: Int, map: HashMap<Vector2, TileInfo>) {
        val areas = ArrayList<Area>()

        val terrains = GameBasics.Terrains.values.filter { it.type === TerrainType.Land && it.name != "Lakes"
                && it.name != "Mountain"}

        while(map.values.any { it.baseTerrain=="" }) // the world could be split into lots off tiny islands, and every island deserves land types
        {
            val emptyTiles = map.values.filter { it.baseTerrain == "" }.toMutableList()
            val numberOfSeeds = ceil(emptyTiles.size / averageTilesPerArea.toFloat()).toInt()
            val maxLatitude = abs(getLatitude(Vector2(distance.toFloat(), distance.toFloat())))

            for (i in 0 until numberOfSeeds) {
                var terrain = if (Math.random() > waterPercent) terrains.getRandom().name
                else "Ocean"
                val tile = emptyTiles.getRandom()

                //change grassland to desert or tundra based on y
                if (abs(getLatitude(tile.position)) < maxLatitude * 0.1) {
                    if (terrain == "Grassland" || terrain == "Tundra")
                        terrain = "Desert"
                } else if (abs(getLatitude(tile.position)) > maxLatitude * 0.7) {
                    if (terrain == "Grassland" || terrain == "Plains" || terrain == "Desert" || terrain == "Ocean") {
                        terrain = "Tundra"
                    }
                } else {
                    if (terrain == "Tundra") terrain = "Plains"
                    else if (terrain == "Desert") terrain = "Grassland"
                }

                val area = Area(terrain)
                emptyTiles -= tile
                area.addTile(tile)
                areas += area
            }

            expandAreas(areas, map)
            expandAreas(areas, map)
        }
    }
}

/**
 * This generator simply generates Perlin noise,
 * "spreads" it out according to the ratio in generateTile,
 * and assigns it as the height of the various tiles.
 * Tiles below a certain height threshold (determined in generateTile, currently 50%)
 *   are considered water tiles, the rest are land tiles
 */
class PerlinNoiseRandomMapGenerator:SeedRandomMapGenerator(){
    override fun generateMap(distance: Int): HashMap<String, TileInfo> {
        val map = HashMap<Vector2, TileInfo>()
        val mapRandomSeed = Random().nextDouble() // without this, all the "random" maps would look the same
        for (vector in HexMath().getVectorsInDistance(Vector2.Zero, distance))
            map[vector] = generateTile(vector,mapRandomSeed)

        divideIntoAreas(6, 0f, map)

        val mapToReturn = HashMap<String, TileInfo>()
        for(tile in map) {
            tile.value.setTransients()
            mapToReturn[tile.key.toString()] = tile.value
        }

        setWaterTiles(mapToReturn)

        for(tile in mapToReturn.values) randomizeTile(tile,mapToReturn)

        return mapToReturn
    }

    private fun generateTile(vector: Vector2, mapRandomSeed: Double): TileInfo {
        val tile=TileInfo()
        tile.position=vector
        val ratio = 1/10.0
        val height = Perlin.noise(vector.x*ratio,vector.y*ratio,mapRandomSeed)
        + Perlin.noise(vector.x*ratio*2,vector.y*ratio*2,mapRandomSeed)/2
        + Perlin.noise(vector.x*ratio*4,vector.y*ratio*4,mapRandomSeed)/4
        when {
            height>0.8 -> tile.baseTerrain = "Mountain"
            height>0   -> tile.baseTerrain = "" // we'll leave this to the area division
            else       -> tile.baseTerrain = "Ocean"
        }
        return tile
    }
}

/**
 * This generator uses the algorithm from the game "Alexander", outlined here:
 * http://www.cartania.com/alexander/generation.html
 */
class AlexanderRandomMapGenerator:RandomMapGenerator(){
    fun generateMap(distance: Int, landExpansionChance:Float): HashMap<String, TileInfo> {
        val map = HashMap<Vector2, TileInfo?>()

        for (vector in HexMath().getVectorsInDistance(Vector2.Zero, distance))
            map[vector] = null

        val sparkList = ArrayList<Vector2>()
        val grassland = "Grassland"
        val ocean = "Ocean"
        for(i in 0..distance*distance/6){
            val location = map.filter { it.value==null }.map { it.key }.getRandom()
            map[location] = TileInfo().apply { baseTerrain= grassland}
            sparkList.add(location)
        }

        while(sparkList.any()){
            val currentSpark = sparkList.getRandom()
            val emptyTilesAroundSpark = HexMath().getAdjacentVectors(currentSpark)
                    .filter { map.containsKey(it) && map[it]==null }
            if(map[currentSpark]!!.baseTerrain==grassland){
                for(tile in emptyTilesAroundSpark){
                    if(Math.random()<landExpansionChance) map[tile]=TileInfo().apply { baseTerrain=grassland }
                    else  map[tile]=TileInfo().apply { baseTerrain=ocean }
                }
            }
            else{
                for(tile in emptyTilesAroundSpark)
                    map[tile]=TileInfo().apply { baseTerrain=ocean }
            }
            sparkList.remove(currentSpark)
            sparkList.addAll(emptyTilesAroundSpark)
        }

        val newmap = HashMap<String,TileInfo>()
        for(entry in map){
            entry.value!!.position = entry.key
            if(entry.value!!.baseTerrain==ocean
                    && HexMath().getAdjacentVectors(entry.key).all { !map.containsKey(it) || map[it]!!.baseTerrain==grassland })
                entry.value!!.baseTerrain=grassland

            newmap[entry.key.toString()] = entry.value!!
        }

        setWaterTiles(newmap)

        return newmap
        // now that we've divided them into land and not-land, stage 2 - seeding areas the way we did with the seed generator!

    }
}

class Area(var terrain: String) {
    val locations = ArrayList<Vector2>()
    fun addTile(tileInfo: TileInfo) {
        locations+=tileInfo.position
        tileInfo.baseTerrain = terrain
    }
}

/**
 * This generator works by creating a number of seeds of different terrain types in random places,
 * and choosing a random one each time to expand in a random direction, until the map is filled.
 * With water, this creates canal-like structures.
 */
open class SeedRandomMapGenerator : RandomMapGenerator() {

    fun generateMap(distance: Int, waterPercent:Float): HashMap<String, TileInfo> {

        val map = HashMap<Vector2, TileInfo>()

        for (vector in HexMath().getVectorsInDistance(Vector2.Zero, distance))
            map[vector] = TileInfo().apply { position=vector; baseTerrain="" }


        divideIntoAreas(6, waterPercent, map)

        val mapToReturn = HashMap<String,TileInfo>()

        for (entry in map) mapToReturn[entry.key.toString()] = entry.value
        for (entry in map) randomizeTile(entry.value, mapToReturn)

        setWaterTiles(mapToReturn)
        return mapToReturn
    }

    open fun divideIntoAreas(averageTilesPerArea: Int, waterPercent: Float, map: HashMap<Vector2, TileInfo>) {
        val areas = ArrayList<Area>()

        val terrains = GameBasics.Terrains.values.filter { it.type === TerrainType.Land && it.name != "Lakes" && it.name != "Mountain" }

        while(map.values.any { it.baseTerrain=="" }) // the world could be split into lots off tiny islands, and every island deserves land types
        {
            val emptyTiles = map.values.filter { it.baseTerrain == "" }.toMutableList()
            val numberOfSeeds = ceil(emptyTiles.size / averageTilesPerArea.toFloat()).toInt()

            for (i in 0 until numberOfSeeds) {
                val terrain = if (Math.random() > waterPercent) terrains.getRandom().name
                else "Ocean"
                val area = Area(terrain)
                val tile = emptyTiles.getRandom()
                emptyTiles -= tile
                area.addTile(tile)
                areas += area
            }

            expandAreas(areas, map)

//             After we've assigned all the tiles, there will be some areas that contain only 1 or 2 tiles.
//             So, we kill those areas, and have the world expand on and cover them too
//            for (area in areas.toList()) {
//                if (area.locations.size < 3) {
//                    areas -= area
//                    for (location in area.locations) map[location]!!.baseTerrain = ""
//                }
//            }
            expandAreas(areas, map)
        }


        for (area in areas.filter { it.terrain == "Ocean" && it.locations.size <= 10 }) {
            // areas with 10 or less tiles are lakes.
            for (location in area.locations)
                map[location]!!.baseTerrain = "Lakes"
        }
    }

    fun expandAreas(areas: ArrayList<Area>, map: HashMap<Vector2, TileInfo>) {
        val expandableAreas = ArrayList<Area>(areas)
        while (expandableAreas.isNotEmpty()) {
            val areaToExpand = expandableAreas.getRandom()
            if(areaToExpand.locations.size>=20){
                expandableAreas -= areaToExpand
                continue
            }
            val availableExpansionVectors = areaToExpand.locations
                    .flatMap { HexMath().getAdjacentVectors(it) }.asSequence().distinct()
                    .filter { map.containsKey(it) && map[it]!!.baseTerrain=="" }.toList()
            if (availableExpansionVectors.isEmpty()) expandableAreas -= areaToExpand
            else {
                val expansionVector = availableExpansionVectors.getRandom()
                areaToExpand.addTile(map[expansionVector]!!)

                val neighbors = HexMath().getAdjacentVectors(expansionVector)
                val areasToJoin = areas.filter {
                    it.terrain == areaToExpand.terrain
                            && it != areaToExpand
                            && it.locations.any { location -> location in neighbors }
                }
                for (area in areasToJoin) {
                    areaToExpand.locations += area.locations
                    areas.remove(area)
                    expandableAreas.remove(area)
                }
            }
        }
    }

}


/**
 * This contains the basic randomizing tasks (add random terrain feature/resource)
 * and a basic map generator where every single tile is individually randomized.
 * Doesn't look very good TBH.
 */
open class RandomMapGenerator {

    private fun addRandomTile(position: Vector2): TileInfo {
        val tileInfo = TileInfo()
        tileInfo.position = position
        val terrains = GameBasics.Terrains.values

        val baseTerrain = terrains.filter { it.type === TerrainType.Land && it.name != "Lakes" }.getRandom()
        tileInfo.baseTerrain = baseTerrain.name

        addRandomTerrainFeature(tileInfo)
        addRandomResourceToTile(tileInfo)

        return tileInfo
    }

    fun addRandomTerrainFeature(tileInfo: TileInfo) {
        if (tileInfo.getBaseTerrain().canHaveOverlay && Math.random() > 0.7f) {
            val secondaryTerrains = GameBasics.Terrains.values
                    .filter { it.type === TerrainType.TerrainFeature && it.occursOn!!.contains(tileInfo.baseTerrain) }
            if (secondaryTerrains.any()) tileInfo.terrainFeature = secondaryTerrains.getRandom().name
        }
    }


    internal fun addRandomResourceToTile(tileInfo: TileInfo) {

        var tileResources = GameBasics.TileResources.values.toList()

        // Resources are placed according to TerrainFeature, if exists, otherwise according to BaseLayer.
        tileResources = tileResources.filter { it.terrainsCanBeFoundOn.contains(tileInfo.getLastTerrain().name) }

        var resource: TileResource? = null
        when {
            Math.random() < 1 / 5f  -> resource = getRandomResource(tileResources, ResourceType.Bonus)
            Math.random() < 1 / 7f  -> resource = getRandomResource(tileResources, ResourceType.Strategic)
            Math.random() < 1 / 15f -> resource = getRandomResource(tileResources, ResourceType.Luxury)
        }
        if (resource != null) tileInfo.resource = resource.name
    }

    private fun getRandomResource(resources: List<TileResource>, resourceType: ResourceType): TileResource? {
        val filtered = resources.filter { it.resourceType == resourceType }
        if (filtered.isEmpty()) return null
        else return filtered.getRandom()
    }

    open fun generateMap(distance: Int): HashMap<String, TileInfo> {
        val map = HashMap<String, TileInfo>()
        for (vector in HexMath().getVectorsInDistance(Vector2.Zero, distance))
            map[vector.toString()] = addRandomTile(vector)
        return map
    }

    fun maybeAddAncientRuins(tile: TileInfo) {
        val baseTerrain = tile.getBaseTerrain()
        if(baseTerrain.type!=TerrainType.Water && !baseTerrain.impassable && Random().nextDouble() < 1f/100)
            tile.improvement = "Ancient ruins"
    }


    fun hasLandTile(map: HashMap<String, TileInfo>, vector: Vector2): Boolean {
        return map.containsKey(vector.toString()) && map[vector.toString()]!!.getBaseTerrain().type == TerrainType.Land
    }

    open fun setWaterTiles(map: HashMap<String, TileInfo>) {
        for (tile in map.values.filter { it.baseTerrain == "Ocean" }) {
            if (HexMath().getVectorsInDistance(tile.position,2).any { hasLandTile(map,it) }) {
                tile.baseTerrain = "Coast"
                tile.setTransients()
            }
        }
    }

    open fun randomizeTile(tileInfo: TileInfo, map: HashMap<String, TileInfo>){
        if(tileInfo.getBaseTerrain().type==TerrainType.Land && Math.random()<0.05f){
            tileInfo.baseTerrain = "Mountain"
            tileInfo.setTransients()
        }
        if(tileInfo.getBaseTerrain().type==TerrainType.Land && Math.random()<0.05f
            && HexMath().getVectorsInDistance(tileInfo.position,1).all { hasLandTile(map,it) }){
            tileInfo.baseTerrain = "Lakes"
            tileInfo.setTransients()
        }
        addRandomTerrainFeature(tileInfo)
        addRandomResourceToTile(tileInfo)
        maybeAddAncientRuins(tileInfo)
    }
}