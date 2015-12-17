package cachemodeler_multyprocessor;

/**
 * структура для определения местоположения блока в кэше
 */
public class BlockAddress
{    
    public int moduleNumber;    //номер модуля, где может храниться блок
    public int tag;             //номер блока в модуле - тэг блока
    
    public BlockAddress(int moduleNumber, int tag)
    {
        this.moduleNumber = moduleNumber;
        this.tag = tag;
    }
}
