package cachemodeler_multyprocessor;

/**
 *
 * @author Taras
 */
//кэш-память (матрица тэгов)
public class CacheMemory
{
    public int[][] tags;
    public char[][] states;
    public int modulesCount;
    private SystemParameters systemParameters;
    
    public CacheMemory(SystemParameters systemParameters)            
    {
        //определяем число модулей
        //число строк в модуле равно количеству входов кэша
        modulesCount = systemParameters.cacheSize / 
                (systemParameters.waysCount * systemParameters.cacheBlockSize);
        //создаем матрицу тэгов
        //строка матрицы - это тэги одного модуля
        tags = new int[modulesCount][];   
        states = new char[modulesCount][];
        for (int i=0; i<modulesCount; i++)
        {
            tags[i] = new int[systemParameters.waysCount];
            states[i] = new char[systemParameters.waysCount];
        }
        //обнуляем все тэги (изначально кэш не заполнен)
        for (int i=0; i<modulesCount; i++)
        {
            for (int j=0; j<systemParameters.waysCount; j++)
            {
                tags[i][j] = -1;
                states[i][j] = 'z';
            }
        }
        this.systemParameters = systemParameters;
    }    
    
    //функция определения возможного местоположения в кэше блока,
    //содержащего байт по заданному адресу
    public synchronized BlockAddress searchBlockPlace(long address)
    {
        //определяем номера блока и модуля
        int blockNumber = (int)(address / systemParameters.cacheBlockSize);
        int moduleNumber = blockNumber % modulesCount;
        //определяем номер блока в модуле - это тэг, который затем нужно будет искать
        int tag = blockNumber / modulesCount;        
        return new BlockAddress(moduleNumber, tag);
    }
    
    //функция поиска блока по заданному местоположению
    //в случае успеха возвращает номер столбца с тэгом в известном модуле
    public synchronized int searchBlock(BlockAddress ba)
    {
        //ищем тэг
        int tagNumber = 0;
        for (int i=0; i<systemParameters.waysCount; i++)
        {
            if (tags[ba.moduleNumber][i] == ba.tag)
            {                
                tagNumber = i;
                return tagNumber;
            }
        }
        return Const.NOT_FOUND;
    }
    
    //функция поиска свободного места в модуле кэша с заданным номером
    //возвращает номер свободного блока или NOT_FOUND
    public synchronized int searchFreePlace(int moduleNumber)
    {
        int freeBlockNumber = 0;
        for (int i=0; i<systemParameters.waysCount; i++)
        {
            if (tags[moduleNumber][i] == -1)
            {
                //нашли свободное место
                freeBlockNumber = i;
                return freeBlockNumber;
            }
        }
        return Const.NOT_FOUND;
    }
    
}
