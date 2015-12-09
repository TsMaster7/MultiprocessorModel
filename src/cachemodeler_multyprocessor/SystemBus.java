package cachemodeler_multyprocessor;

import java.util.*;
import java.util.Collections;
import javax.swing.JTextArea;

/**
 *
 * @author Taras
 */
//системная шина - служит для связи отдельных процессоров (и кэш-контроллеров)
//с основной памятью и между собой,
//обслуживает запросы к памяти
public class SystemBus implements Runnable
{
    //текстовая область для вывода отчета
    JTextArea reportText;
    //поток для системной шины
    private Thread systemBusThread;
    //набор кэш-контроллеров, связанных с шиной
    private CacheController[] cacheControllers;  
    //очередь запроссов
    private LinkedList<Query> queries;
    //параметры моделирования
    private ModelingParameters modelingParameters;
    //параметры системы
    private SystemParameters systemParameters;
    //счетчики обращений к блокам
    private int sharedQueries;
    private int allQueries;
    //счетчики для учета состояний блоков (только протоколы MSI и MESI)
    private int mStateCounter;
    private int sStateCounter;
    private int iStateCounter;
    private int eStateCounter;
    
    
    public SystemBus(ModelingParameters modelingParameters, SystemParameters sp,
            JTextArea logText)
    {
        this.modelingParameters = modelingParameters;
        this.systemParameters = sp;
        systemBusThread = new Thread(this, "system_bus");
        queries = new LinkedList<Query>();
        this.reportText = logText;
        sharedQueries = allQueries = 0;
        mStateCounter = sStateCounter = iStateCounter = this.eStateCounter = 0;
    }
    
    //добавляет новый запрос в конец очереди
    public synchronized void addQuery(Query newQuery)
    {
        queries.add(newQuery);                
    }
    
    //удаляет и возвращает первый запрос из очереди
    //если очередь пуста возвращает null
    public synchronized Query getQuery()
    {
        return queries.pollFirst();
    }
    
    public void setControllers(CacheController[] cacheControllers)
    {
        this.cacheControllers = cacheControllers;
    }
    
    public boolean isControllersAllive()
    {
        boolean controllersAllive = false;
        for (int i=0; i<cacheControllers.length; i++)
        {
            controllersAllive = 
                    controllersAllive || cacheControllers[i].getCacheControllerThread().isAlive();          
        }        
        return controllersAllive;
    }
    
    public void run()
    {    
        while(true)
        {
            if (isControllersAllive())   //продолжаем работу
            {
                if(queries.isEmpty())
                //очередь пуста - делаем паузу перед следующим тактом и продолжаем цикл
                //обработки запросов к шине
                {
                    try
                    {
                        systemBusThread.sleep(5 * modelingParameters.systemBusDelay);
                    }
                    catch (Exception ex) {}
                    finally
                    {
                        continue;
                    }
                }
                else
                //в очереди есть запросы - обслуживаем их 
                {    
                    Query curQuery;                     
                    //получаем запрос из очереди
                    curQuery = getQuery();
                   
                    //получаем массив номеров контроллеров, запросы от которых есть
                    //в очереди (кроме обрабатываемого)
                    //соответствующие процессоры будут ожидать еще и обработки текущего запроса
                    boolean isWaiting = queries.isEmpty() ? false : true;
                    int[] waitingProcessorsNumbers = null;
                    Object[] othersQueries;
                    if (isWaiting)
                    {                        
                        othersQueries = (Collections.synchronizedList(queries)).toArray();                        
                        //создаем массив номеров процессоров, запросы от которых
                        //находятся в очереди                        
                        waitingProcessorsNumbers = new int[othersQueries.length];                        
                        for (int i=0; i<waitingProcessorsNumbers.length; i++)
                        {
                            waitingProcessorsNumbers[i] = ((Query)(othersQueries[i])).number;                               
                        }                                            
                    }
                    //определяем местоположение блока
                    BlockAddress ba =      //номер модуля и тэг
                            cacheControllers[curQuery.number].getCacheMemory().searchBlockPlace(curQuery.address);
                    int colNumber =        //номер ячейки с блоком в модуле кэша (если он там есть)
                            cacheControllers[curQuery.number].getCacheMemory().searchBlock(ba);
                                   
                    //увеличиваем счетчик запросов
                    allQueries++;
                    //определяем, разделяемый ли блок
                    for (int i=0; i<cacheControllers.length; i++)
                    {
                        if (i == curQuery.number) continue;
                        //если в кэше какого-то процессора есть запрашиваемый блок,
                        //то увеличиваем число запросов к разделяемым блокам                        
                        if (cacheControllers[i].getCacheMemory().searchBlock(ba)!= Const.NOT_FOUND)
                        {
                            sharedQueries++;
                            break;
                        }                                        
                    }    
                    
                    //далее действуем в соответствии с протоколом когерентности
                    switch (systemParameters.coherenceProtocol)
                    {
                        case BROADCAST:
                        {
                            //реализация протокола широковещательной рассылки
                            switch (curQuery.queryType)
                            {
                                case READ_HIT:   //попадание при чтении
                                {                                    
                                    if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] != 'i')
                                    //если блок в кэш-памяти достоверен
                                    //просто производим чтение из кэша
                                    {
                                         cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime + modelingParameters.bufferOperationTime);  
                                    }
                                    else
                                    //блок в состоянии Invalid
                                    //требуется чтение из основной памяти и остальные процессоры ожидают
                                    //его окончания так как задействована шина
                                    {
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime + modelingParameters.bufferOperationTime); 
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.memoryCycleTime);
                                        //изменяем состояние блока на Valid
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'v';
                                        //увеличиваем время простоя остальных процессоров на эту же величину
                                        if (isWaiting)
                                        {
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }
                                        }                                      
                                    }
                                }   // конец READ_HIT / BROADCAST !!!
                                    break;
                                    
                                case READ_MISS:    //промах при чтении
                                {
                                    //определяем, есть ли в кэше место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Valid
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 'v';                                        
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime + 2 * modelingParameters.bufferOperationTime);
                                        //увеличичваем также счетчики остальных ожидающих процессоров
                                        if (isWaiting)
                                        {
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }
                                        }                                      
                                    }
                                    else
                                    //один из блоков должен быть вытеснен
                                    //выбираем его случайным образом из всех блоков нужного модуля
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 'v'; 
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime + 2 * modelingParameters.bufferOperationTime);
                                        if (isWaiting)
                                        {
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.searchCachePointerTime);
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }
                                        }                                        
                                    }                                    
                                }     // конец READ_MISS / BROADCAST !!!
                                    break;
                                    
                                case WRITE_HIT:   //попадание при записи
                                {
                                    //производим запись в кэш и в основную память     
                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime + modelingParameters.bufferOperationTime);
                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                                                                                           
                                    //все остальные контроллеры ищут записываемый блок в своем кэше
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime);    
                                        //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                        //то этот блок переходит в состояние invalid
                                        int invNum;
                                        if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i';                                             
                                        }                                        
                                    }    
                                    //кроме того, все ожидающие процессоры простаивают во время цикла записи в ОП
                                    if (isWaiting)
                                    {
                                        for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                        {                                        
                                            cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                 modelingParameters.memoryCycleTime);
                                        }
                                    }
                                    //состояние блока в собственном кэше после записи - всегда Valid
                                    cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'v';
                                }
                                    break;     // конец WRITE_HIT / BROADCAST !!!
                                    
                                case WRITE_MISS:    //промах записи
                                {
                                    //действуем аналогично ситуации с промахом чтения и, кроме того,
                                    //анулируем блоки в других кэшах
                                    //определяем, есть ли в кэше место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Valid
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 'v';                                        
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);     //запись в ОП
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime + modelingParameters.bufferOperationTime);   //запись в кэш                                        

                                    }
                                    else
                                    //один из блоков должен быть вытеснен
                                    //выбираем его случайным образом из всех блоков нужного модуля
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 'v'; 
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime + 2 * modelingParameters.bufferOperationTime);                                        
                                    }    
                                    //все ожидающие ждут окончания цикла памяти
                                    if (isWaiting)
                                    {
                                        for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                        {                                            
                                            cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                 modelingParameters.memoryCycleTime);
                                        }
                                    }
                                    //все контроллеры, кроме текущего ищут записываемый блок в своем кэше
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime);    
                                        //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                        //то этот блок переходит в состояние invalid                                        
                                        int invNum;
                                        if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i'; 
                                        }                                        
                                    }    
                                }
                                    break;    // конец WRITE_MISS / BROADCAST !!! 
                            }   
                        }   //конец реализации BROADCAST !!!!!!!
                            break;     
                            
                        //протокол однократной записи
                        case WRITE_ONCE:
                        {
                            switch (curQuery.queryType)
                            {
                                case READ_HIT:    //попадание чтения - действуем аналогично предыдущему протоколу
                                {
                                    if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] != 'i')
                                    //если блок в кэш-памяти достоверен
                                    //просто производим чтение из кэша
                                    {
                                         cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime + modelingParameters.bufferOperationTime); 
                                      
                                    }
                                    else
                                    //блок в состоянии Invalid
                                    //требуется чтение из основной памяти и остальные процессоры ожидают
                                    //его окончания так как задействована шина
                                    {
                                        //процессор ищиет блок в своем кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime); 
                                        //происходит чтение из основной памяти и запись в кэш - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.memoryCycleTime + modelingParameters.bufferOperationTime);
                                        //процессор читает данные, записанные в кэш - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.bufferOperationTime);
                                         
                                        //изменяем состояние блока на Valid
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'v';
                                        
                                        //увеличиваем время простоя остальных процессоров на эту же величину
                                        if (isWaiting)
                                        {
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime + modelingParameters.bufferOperationTime);
                                            }
                                        }
                                    }
                                }
                                    break;    // конец READ_HIT / WRITE_ONCE !!! 
                                    
                                case READ_MISS:    //промах чтения
                                {
                                    //определяем, есть ли этот блок в состоянии Dirty в каком-либо другом кэше
                                    //это время все процессоры системы будут простаивать
                                    boolean dirtyFound = false;
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        //простой во время поиска указателя
                                        cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                        int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                        if (colNum != Const.NOT_FOUND && 
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'd')
                                        //найден кэш с копией блока в состоянии Dirty
                                        {            
                                            dirtyFound = true;
                                            //записываем его в основную память и изменяем его состояние на Valid
                                            //во время записи в ОП шина будет занята
                                            //но читающий процессор сможет получить блок непосредственно из этого кэша
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 'v';
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    if (j==curQuery.number) continue;
                                                    cacheControllers[j].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime);
                                                }
                                            }
                                            break;
                                        }
                                    }
                                    //если в каком-либо кэше есть блок в состоянии reserved меняем его на valid
                                    //эта операция происходит одновременно с поиском указателя
                                    //и не требует дополнительных затрат времени
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                        if (colNum != Const.NOT_FOUND && 
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'r')
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 'v';
                                        }
                                    }
                                    
                                    //определяем, есть ли в кэше читающего процессора место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Valid
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 'v';                                        
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //чтение из основной памяти - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //чтение из кэша - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);       
                                        //если грязный блок не был обнаружен в другом кэше
                                        //увеличиваем счетчики простоя ожидающих процессоров на время цикла памяти
                                        //в противном случае они уже и так увеличены
                                        if (!dirtyFound && isWaiting)
                                        {                                            
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }                                            
                                        }
                            
                                    }
                                    else
                                    //требуется вытеснение блока
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        //если вытесняемый блок в состоянии Dirty перезаписываем его в ОП
                                        if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] == 'd')
                                        {
                                            //процессор простаивает во время вытеснения блока
                                            cacheControllers[curQuery.number].getProcessor().addUnload(modelingParameters.memoryCycleTime);  
                                            //остальные процессоры также простаивают это время
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(modelingParameters.memoryCycleTime);
                                                }
                                            }
                                        }
                                        //записываем новый блок
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 'v'; 
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //запись нового блока в кэш - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //чтение данных из кэша - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);
                                        //если грязный блок не был обнаружен в другом кэше
                                        //увеличиваем счетчики простоя ожидающих процессоров на время цикла памяти
                                        //в противном случае они уже и так увеличены
                                        if (!dirtyFound && isWaiting)
                                        {                                            
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }                                            
                                        }                                        
                                    }      
                                }
                                    break;      // конец READ_MISS / WRITE_ONCE !!! 
                                    
                                case WRITE_HIT:
                                {
                                    //выполняем поиск указателя в кэше - шина свободна
                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                            modelingParameters.searchCachePointerTime);
                                    if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] == 'v')
                                    //блок в состоянии Valid
                                    {     
                                        //изменяем состояние блока на Reserved
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'r';
                                        //выполняем запись в кэш - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                            modelingParameters.bufferOperationTime);
                                        //записываем блок в ОП - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                            modelingParameters.memoryCycleTime);
                                        //остальные процессоры ждут во время записи в ОП
                                        if (isWaiting)
                                        {
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }
                                        }
                                    }
                                    else
                                    //блок в состоянии reserved, dirty, invalid
                                    {        
                                        //изменяем состояние блока на Dirty
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'd';
                                        //выполняем запись в кэш - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                            modelingParameters.bufferOperationTime);
                                        //запись в ОП здесь не требуется
                                    }
                                    //все остальные контроллеры ищут записываемый блок в своем кэше
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime);    
                                        //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                        //то этот блок переходит в состояние invalid
                                        int invNum;
                                        if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i'; 
                                        }                                        
                                    }   
                                }
                                    break;     // конец WRITE_HIT / WRITE_ONCE !!! 
                                    
                                case WRITE_MISS:     //промах записи
                                {
                                    //действуем аналогично ситуации с промахом чтения и, кроме того,
                                    //анулируем блоки в других кэшах
                                    boolean dirtyFound = false;
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        //простой во время поиска указателя
                                        cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                        int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                        if (colNum != Const.NOT_FOUND && 
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'd')
                                        //найден кэш с копией блока в состоянии Dirty
                                        {            
                                            dirtyFound = true;
                                            //записываем его в основную память и изменяем его состояние на Invalid
                                            //так как он сразу же будет изменен другим кэшем
                                            //во время записи в ОП шина будет занята
                                            //но читающий процессор сможет получить блок непосредственно из этого кэша
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 'i';
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    if (j==curQuery.number) continue;
                                                    cacheControllers[j].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime);
                                                }
                                            }
                                            break;
                                        }
                                    }
                                    //определяем, есть ли в кэше место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Dirty
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 'd';                                        
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);     //запись из ОП 
                                        cacheControllers[curQuery.number].getProcessor().addUnload(   //запись и перезапись в кэш                           
                                                modelingParameters.searchCachePointerTime + 2 * modelingParameters.bufferOperationTime);   
                                       
                                        //увеличичваем счетчики простоя ожидающих процессоров 
                                        if (!dirtyFound && isWaiting)
                                        {
                                            for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                            {                                                
                                                cacheControllers[j].getProcessor().addUnload(
                                                        modelingParameters.memoryCycleTime);
                                            }
                                        }
                                    }
                                    else
                                    //требуется вытеснение блока                                    
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        //если вытесняемый блок в состоянии Dirty перезаписываем его в ОП
                                        if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] == 'd')
                                        {
                                            //процессор простаивает во время вытеснения блока
                                            cacheControllers[curQuery.number].getProcessor().addUnload(modelingParameters.memoryCycleTime);  
                                            //остальные процессоры также простаивают это время
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(modelingParameters.memoryCycleTime);
                                                }
                                            }
                                        }
                                        //записываем новый блок
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 'd'; 
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //запись нового блока в кэш - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //запись данных в кэш - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);
                                        //если грязный блок не был обнаружен в другом кэше
                                        //увеличиваем счетчики простоя ожидающих процессоров на время цикла памяти
                                        //в противном случае они уже и так увеличены
                                        if (!dirtyFound && isWaiting)
                                        {                                            
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }                                            
                                        }                                        
                                    }  
                                    //все остальные контроллеры ищут записываемый блок в своем кэше
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime);    
                                        //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                        //то этот блок переходит в состояние invalid
                                        int invNum;
                                        if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i'; 
                                        }                                        
                                    }                                      
                                }
                                    break;           //конец WRITE_MISS / WRITE_ONCE !!!
                            }                            
                        }
                            break;     //   конец реализации WRITE ONCE
 /////////////////////////////////////////////////////////////////////////////////////////////////////        
                        case MSI:
                        {
                            switch (curQuery.queryType)
                            {
                                case READ_HIT:
                                //действуем аналогично протоколу однократной записи
                                //с заменой состояния Shared на Valid
                                {
                                    if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] != 'i')
                                    //если блок в кэш-памяти достоверен
                                    //просто производим чтение из кэша
                                    {
                                        switch (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber])
                                        {
                                            case 'm':
                                                this.mStateCounter++;
                                                break;
                                            case 's':
                                                this.sStateCounter++;
                                        }
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime + modelingParameters.bufferOperationTime); 
                                       
                                    }
                                    else
                                    //блок в состоянии Invalid
                                    //требуется чтение из основной памяти и остальные процессоры ожидают
                                    //его окончания так как задействована шина
                                    {
                                        this.iStateCounter++;
                                        //процессор ищиет блок в своем кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime); 
                                        //ищем блок в состоянии modified в других кэшах
                                        boolean modifFound = false;
                                        for (int i=0; i<cacheControllers.length; i++)
                                        {
                                            //простой во время поиска указателя
                                            cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                            int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                            if (colNum != Const.NOT_FOUND && 
                                                    cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'm')
                                            //найден кэш с копией блока в состоянии Modified
                                            {            
                                                modifFound = true;
//                                                reportText.append(" - блок в состоянии modified обнаружен в кэше " + i + "\n");   
//                                                reportText.append(" - записываем блок в ОП изменяем его состояние на Shared " + i + "\n");
                                                //записываем его в основную память и изменяем его состояние на Shared
                                                //во время записи в ОП шина будет занята
                                                //но читающий процессор сможет получить блок непосредственно из этого кэша
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 's';
                                                if (isWaiting)
                                                {
                                                    for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                    {
                                                        if (j==curQuery.number) continue;
                                                        cacheControllers[j].getProcessor().addUnload(
                                                                modelingParameters.memoryCycleTime);
                                                    }
                                                }
                                                //одновременно с этим производим чтение блока текущим процессором                                                
                                                cacheControllers[curQuery.number].getProcessor().addUnload(
                                                        modelingParameters.memoryCycleTime + modelingParameters.bufferOperationTime);
                                                break;
                                            }
                                        }                                   
                                        //в любом случае изменяем состояние блока в текущем кэше на shared
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 's';
                                        
                                        if (!modifFound)
                                        {
                                            cacheControllers[curQuery.number].getProcessor().addUnload(
                                                        modelingParameters.memoryCycleTime + modelingParameters.bufferOperationTime);
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    if (j==curQuery.number) continue;
                                                    cacheControllers[j].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime);
                                                }            
                                            }
                                        }
                                    }                                    
                                }
                                    break;     //конец реализации READ_HIT / MSI
                                
                                case READ_MISS:     //промах чтения - MSI
                                {
                                    //определяем, есть ли этот блок в состоянии Modified в каком-либо другом кэше
                                    //это время все процессоры системы будут простаивать
                                    boolean modifFound = false;
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        //простой во время поиска указателя
                                        cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                        int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                        if (colNum != Const.NOT_FOUND && 
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'm')
                                        //найден кэш с копией блока в состоянии modified
                                        {            
                                            modifFound = true;
                                            //записываем его в основную память и изменяем его состояние на Shared
                                            //во время записи в ОП шина будет занята
                                            //но читающий процессор сможет получить блок непосредственно из этого кэша
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 's';
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    if (j==curQuery.number) continue;
                                                    cacheControllers[j].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime);
                                                }
                                            }
                                            break;
                                        }
                                    }                  
                                    
                                    //определяем, есть ли в кэше читающего процессора место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Shared
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 's';                                        
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //чтение из основной памяти - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //чтение из кэша - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);       
                                        //если грязный блок не был обнаружен в другом кэше
                                        //увеличиваем счетчики простоя ожидающих процессоров на время цикла памяти
                                        //в противном случае они уже и так увеличены
                                        if (!modifFound && isWaiting)
                                        {                                            
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }                                            
                                        }                        
                                    }
                                    else
                                    //требуется вытеснение блока
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        
                                        //запоминаем тэг вытесняемого блока
                                        int replTag = cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock];
                                        //если вытесняемый блок в состоянии modified перезаписываем его в ОП
                                        if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] == 'm')
                                        {
                                            //процессор простаивает во время вытеснения блока
                                            cacheControllers[curQuery.number].getProcessor().addUnload(modelingParameters.memoryCycleTime);  
                                            //остальные процессоры также простаивают это время
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(modelingParameters.memoryCycleTime);
                                                }
                                            }
                                            //анулируем все его копии в других кэшах
                                            for (int k=0; k<cacheControllers.length; k++)
                                            {
                                                if (k == curQuery.number) continue;
                                                int replCol = cacheControllers[k].getCacheMemory().searchBlock(new BlockAddress(ba.moduleNumber, replTag));
                                                if (replCol != Const.NOT_FOUND) 
                                                {
                                                    cacheControllers[k].getCacheMemory().states[ba.moduleNumber][replCol] = 'i';
                                                }
                                            }
                                        }
                                        //записываем новый блок
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 's'; 
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //запись нового блока в кэш - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //чтение данных из кэша - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);
                                        //если грязный блок не был обнаружен в другом кэше
                                        //увеличиваем счетчики простоя ожидающих процессоров на время цикла памяти
                                        //в противном случае они уже и так увеличены
                                        if (!modifFound && isWaiting)
                                        {                                            
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }                                            
                                        }       

                                    }                                  
                                }
                                    break;     //конец реализации READ_MISS / MSI
                                    
                                case WRITE_HIT:    //попадание при записи - MSI
                                {
                                    switch (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber])
                                    {
                                        case 'm':
                                            this.mStateCounter++;
                                            break;
                                        case 'i':
                                            this.iStateCounter++;
                                            break;
                                        case 's':
                                            this.sStateCounter++;
                                            break;
                                    }
                                    //выполняем поиск указателя в кэше - шина свободна
                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                            modelingParameters.searchCachePointerTime);                                       
                                    //изменяем состояние блока на Modified
                                    cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'm';
                                    //выполняем запись в кэш - шина свободна
                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                        modelingParameters.bufferOperationTime);        
                                          
                                    //все остальные контроллеры ищут записываемый блок в своем кэше
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime);    
                                        //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                        //то этот блок переходит в состояние invalid
                                        int invNum;
                                        if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i'; 
                                        }                                        
                                    }                              
                                }
                                    break;  //конец реализации WRITE_HIT / MSI
                                    
                                case WRITE_MISS:    // промах при записи - MSI
                                {
                                    //действуем аналогично ситуации с промахом чтения и, кроме того,
                                    //анулируем блоки в других кэшах
                                    boolean modifFound = false;
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        //простой во время поиска указателя
                                        cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                        int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                        if (colNum != Const.NOT_FOUND && 
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'm')
                                        //найден кэш с копией блока в состоянии Modified
                                        //он непосредственно будет поставлять этот блок в запрашивающий кэш через шину 
                                        {            
                                            modifFound = true;
                                            //изменяем его состояние на Invalid                                            
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 'i';                                            
                                        }
                                    }
                                    //определяем, есть ли в кэше место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Modified
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 'm';                                        
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        if (modifFound)
                                        //блок был поставлен из другого кэша
                                        {
                                            cacheControllers[curQuery.number].getProcessor().addUnload(
                                                    modelingParameters.bufferOperationTime + modelingParameters.searchCachePointerTime);
                                        }
                                        else
                                        //блок был прочитан из памяти
                                        {
                                            cacheControllers[curQuery.number].getProcessor().addUnload(
                                                    modelingParameters.bufferOperationTime + 
                                                    modelingParameters.memoryCycleTime +
                                                    modelingParameters.searchCachePointerTime);                                            
                                        }
                                        
                                        //увеличичваем счетчики простоя ожидающих процессоров   
                                        if (isWaiting)
                                        {
                                            for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                            {                                                
                                                if (!modifFound)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime);
                                                }
                                                else
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(
                                                        modelingParameters.bufferOperationTime + 
                                                        modelingParameters.searchCachePointerTime);                                                
                                                }
                                            }
                                        }
                                    }
                                    else
                                    //требуется вытеснение блока                                    
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        //если вытесняемый блок в состоянии Modified перезаписываем его в ОП
                                        if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] == 'm')
                                        {
                                            //процессор простаивает во время вытеснения блока
                                            cacheControllers[curQuery.number].getProcessor().addUnload(modelingParameters.memoryCycleTime);  
                                            //остальные процессоры также простаивают это время
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(modelingParameters.memoryCycleTime);
                                                }
                                            }
                                        }
                                        //записываем новый блок
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 'm'; 
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //запись нового блока в кэш - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //запись данных в кэш - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);
                                        //увеличиваем счетчики простоя ожидающих процессоров     
                                        if (isWaiting)
                                        {
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                if (!modifFound)
                                                {
                                                    cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                         modelingParameters.memoryCycleTime);                                                                                    
                                                }
                                                else
                                                {
                                                    cacheControllers[i].getProcessor().addUnload(
                                                        modelingParameters.bufferOperationTime + 
                                                        modelingParameters.searchCachePointerTime);                                                
                                                }
                                            }           
                                        }
                                    }  
                                    //все остальные контроллеры ищут записываемый блок в своем кэше
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime);    
                                        //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                        //то этот блок переходит в состояние invalid
                                        int invNum;
                                        if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i'; 
                                        }                                        
                                    }                                            
                                }
                                    break;  //конец реализации WRITE_MISS / MSI
                            }
                        }
                            break;
                            
                        case MESI:   
                        //протокол MESI - расширение протокола MSI
                        //за счет введения дополнительного состояния Exclusive
                        {
                            switch (curQuery.queryType)
                            {
                                case READ_HIT:   //попадание чтения - MESI
                                {
                                    switch (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber])
                                    {
                                        case 'm':
                                            this.mStateCounter++;
                                            break;
                                        case 'i':
                                            this.iStateCounter++;
                                            break;
                                        case 's':
                                            this.sStateCounter++;
                                            break;
                                        case 'e':
                                            this.eStateCounter++;
                                            break;
                                    }
                                    if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] != 'i')
                                    //если блок в кэш-памяти достоверен
                                    //просто производим чтение из кэша
                                    {
                                         cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime + modelingParameters.bufferOperationTime); 
                                     
                                    }
                                    else
                                    //блок в состоянии Invalid
                                    //требуется чтение из основной памяти и остальные процессоры ожидают
                                    //его окончания так как задействована шина
                                    {
                                        //процессор ищиет блок в своем кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime); 
                                        //ищем блок в состоянии modified в других кэшах
                                        boolean modifFound = false;
                                        boolean blockFound = false;
                                        for (int i=0; i<cacheControllers.length; i++)
                                        {
                                            //простой во время поиска указателя
                                            cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                            int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                            if (colNum != Const.NOT_FOUND)
                                            //найден кэш с копией блока
                                            {            
                                                blockFound = true;
                                                if (cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'm')
                                                {
                                                    //копия в другом кэше модифицирована
                                                    modifFound = true;
                                                    //записываем его в основную память и изменяем его состояние на Shared
                                                    //во время записи в ОП шина будет занята
                                                    //но читающий процессор сможет получить блок непосредственно из этого кэша
                                                    cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 's';
                                                    if (isWaiting)
                                                    {
                                                        for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                        {
                                                            if (j==curQuery.number) continue;
                                                            cacheControllers[j].getProcessor().addUnload(
                                                                    modelingParameters.memoryCycleTime);
                                                        }
                                                    }
                                                    //одновременно с этим производим чтение блока текущим процессором                                                
                                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime + modelingParameters.bufferOperationTime);
                                                    break;
                                                }
                                            }
                                        }                                   
                                        //изменяем состояние блока в текущем кэше на shared, либо на exclusive
                                        //в зависимости от того, есть ли его копия в другом кэше
                                        if (blockFound)
                                        {
                                            cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 's';
                                        }
                                        else
                                        {
                                            cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'e';
                                        }
                                        
                                        if (!modifFound)   //если не найден модифицированный блок в другом кэше
                                        //читаем его из памяти
                                        {
                                            cacheControllers[curQuery.number].getProcessor().addUnload(
                                                        modelingParameters.memoryCycleTime + modelingParameters.bufferOperationTime);
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    if (j==curQuery.number) continue;
                                                    cacheControllers[j].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime);
                                                }
                                            }
                                        }
                                    }
                                }
                                    break;
                                    
                                case READ_MISS:   //промах чтения - MESI
                                {
                                    //определяем, есть ли этот блок в состоянии Modified в каком-либо другом кэше
                                    //это время все процессоры системы будут простаивать
                                    boolean modifFound = false;
                                    boolean blockFound = false;
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        //простой во время поиска указателя
                                        cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                        int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                        if (colNum != Const.NOT_FOUND)
                                        //найден кэш с копией блока
                                        {      
                                            blockFound = true;
                                            if (cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'm')
                                            //копия блока в состоянии Modified
                                            {
                                                modifFound = true;
                                                //записываем его в основную память и изменяем его состояние на Shared
                                                //во время записи в ОП шина будет занята
                                                //но читающий процессор сможет получить блок непосредственно из этого кэша
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 's';
                                                if (isWaiting)
                                                {
                                                    for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                    {
                                                        if (j==curQuery.number) continue;
                                                        cacheControllers[j].getProcessor().addUnload(
                                                                modelingParameters.memoryCycleTime);
                                                    }
                                                }
                                                break;
                                            }
                                        }
                                    }                  
                                    
                                    //определяем, есть ли в кэше читающего процессора место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Shared, Exclusive
                                        if (blockFound)
                                        {
                                            cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 's';                                             
                                        }
                                        else
                                        {
                                            cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 'e'; 
                                        }
                                                                                                                               
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //чтение из основной памяти - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //чтение из кэша - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);       
                                        //если грязный блок не был обнаружен в другом кэше
                                        //увеличиваем счетчики простоя ожидающих процессоров на время цикла памяти
                                        //в противном случае они уже и так увеличены
                                        if (!modifFound && isWaiting)
                                        {                                            
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }                                            
                                        }
                                    }
                                    else
                                    //требуется вытеснение блока
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        
                                        //запоминаем тэг вытесняемого блока
                                        int replTag = cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock];
                                        //если вытесняемый блок в состоянии modified перезаписываем его в ОП
                                        if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] == 'm')
                                        {
                                            //процессор простаивает во время вытеснения блока
                                            cacheControllers[curQuery.number].getProcessor().addUnload(modelingParameters.memoryCycleTime);  
                                            //остальные процессоры также простаивают это время
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(modelingParameters.memoryCycleTime);
                                                }
                                            }
                                            //анулируем все его копии в других кэшах
                                            for (int k=0; k<cacheControllers.length; k++)
                                            {
                                                if (k == curQuery.number) continue;
                                                int replCol = cacheControllers[k].getCacheMemory().searchBlock(new BlockAddress(ba.moduleNumber, replTag));
                                                if (replCol != Const.NOT_FOUND) 
                                                {
                                                    cacheControllers[k].getCacheMemory().states[ba.moduleNumber][replCol] = 'i';
                                                }
                                            }
                                        }
                                        else
                                        //вытесняемый блок не модифицирован, может стать эксклюзивным
                                        {
                                            //еще раз просматриваем все кэши, если вытесненный блок в них в единственном экземпляре
                                            //переводим его в состояние exclusive
                                            int replCount = 0;
                                            int replCol = 0;
                                            int lastReplCol = 0;
                                            int lastReplFound = 0;
                                            for (int k=0; k<cacheControllers.length; k++)
                                            {
                                                if (k == curQuery.number) continue;
                                                replCol = cacheControllers[k].getCacheMemory().searchBlock(new BlockAddress(ba.moduleNumber, replTag));
                                                if (replCol != Const.NOT_FOUND) 
                                                {
                                                   replCount++;
                                                   lastReplFound = k;
                                                   lastReplCol = replCol;
                                                }
                                            }
                                            if (replCount == 1)
                                            {                                                
                                                try
                                                {
                                                    cacheControllers[lastReplFound].getCacheMemory().states[ba.moduleNumber][lastReplCol] = 'e';                                            
                                                }
                                                catch (Exception ex)
                                                {
                                                    reportText.append(" --- !!! --- " + lastReplFound + " " + ba.moduleNumber + " " + replCol + "\n");                                                    
                                                }
                                            }
                                        }
                                                                                
                                        //записываем новый блок
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        if (blockFound)
                                        {
                                            cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 's';                                             
                                        }
                                        else
                                        {
                                            cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 'e'; 
                                        }
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //запись нового блока в кэш - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //чтение данных из кэша - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);
                                        //если грязный блок не был обнаружен в другом кэше
                                        //увеличиваем счетчики простоя ожидающих процессоров на время цикла памяти
                                        //в противном случае они уже и так увеличены
                                        if (!modifFound && isWaiting)
                                        {                                            
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                     modelingParameters.memoryCycleTime);
                                            }                                            
                                        }                                        
                                    }                                          
                                }
                                    break;
                                    
                                case WRITE_HIT:    //попадание записи - MESI
                                {
                                    switch (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber])
                                    {
                                        case 'm':
                                            this.mStateCounter++;
                                            break;
                                        case 'i':
                                            this.iStateCounter++;
                                            break;
                                        case 's':
                                            this.sStateCounter++;
                                            break;
                                        case 'e':
                                            this.eStateCounter++;
                                            break;
                                    }
                                    //выполняем поиск указателя в кэше - шина свободна
                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                            modelingParameters.searchCachePointerTime);                                       
                                    char state = cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber];
                                    //изменяем состояние блока на Modified
                                    cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][colNumber] = 'm';
                                    //выполняем запись в кэш - шина свободна
                                    cacheControllers[curQuery.number].getProcessor().addUnload(
                                        modelingParameters.bufferOperationTime);        
                                          
                                    //если блок не в состоянии Exclusive все остальные контроллеры ищут записываемый блок в своем кэше
                                    if (state != 'e')
                                    {
                                        for (int i=0; i<cacheControllers.length; i++)
                                        {
                                            if (i == curQuery.number) continue;
                                            cacheControllers[i].getProcessor().addUnload(
                                                 modelingParameters.searchCachePointerTime);    
                                            //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                            //то этот блок переходит в состояние invalid
                                            int invNum;
                                            if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                            {
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i'; 
                                            }                                        
                                        }
                                    }
                                }
                                    break;
                                    
                                case WRITE_MISS:    //промах записи - MESI
                                {
                                    boolean modifFound = false;
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        //простой во время поиска указателя
                                        cacheControllers[i].getProcessor().addUnload(modelingParameters.searchCachePointerTime);
                                        int colNum = cacheControllers[i].getCacheMemory().searchBlock(ba);
                                        if (colNum != Const.NOT_FOUND && 
                                                cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] == 'm')
                                        //найден кэш с копией блока в состоянии Modified
                                        //он непосредственно будет поставлять этот блок в запрашивающий кэш через шину 
                                        {            
                                            modifFound = true;
                                            //изменяем его состояние на Invalid                                            
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][colNum] = 'i';                                            
                                        }
                                    }
                                    //определяем, есть ли в кэше место для нового блока
                                    int newCol;
                                    if ((newCol = cacheControllers[curQuery.number].getCacheMemory().searchFreePlace(ba.moduleNumber)) 
                                            != Const.NOT_FOUND)
                                    //вытеснение блока не требуется, заносим его в кэш
                                    {
                                        //устанавливаем тэг загружаемого блока в свободную строку кэша
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][newCol] = ba.tag;                                        
                                        //устанавливаем состояние загруженного блока в Modified
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][newCol] = 'm';                                        
                                        //увеличиваем счетчик тактов простоя для текущего процессора
                                        if (modifFound)
                                        //блок был поставлен из другого кэша
                                        {
                                            cacheControllers[curQuery.number].getProcessor().addUnload(
                                                    modelingParameters.bufferOperationTime + modelingParameters.searchCachePointerTime);
                                        }
                                        else
                                        //блок был прочитан из памяти
                                        {
                                            cacheControllers[curQuery.number].getProcessor().addUnload(
                                                    modelingParameters.bufferOperationTime + 
                                                    modelingParameters.memoryCycleTime +
                                                    modelingParameters.searchCachePointerTime);                                            
                                        }
                                        
                                        //увеличичваем счетчики простоя ожидающих процессоров      
                                        if (isWaiting)
                                        {
                                            for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                            {                                                
                                                if (!modifFound)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(
                                                            modelingParameters.memoryCycleTime);
                                                }
                                                else
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(
                                                        modelingParameters.bufferOperationTime + 
                                                        modelingParameters.searchCachePointerTime);                                                
                                                }
                                            }   
                                        }
                                    }
                                    else
                                    //требуется вытеснение блока                                    
                                    {
                                        int replBlock = new Random().nextInt(systemParameters.waysCount);
                                        
                                        //запоминаем тэг вытесняемого блока
                                        int replTag = cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock];
                                        //если вытесняемый блок в состоянии Modified перезаписываем его в ОП
                                        if (cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] == 'm')
                                        {
                                            //процессор простаивает во время вытеснения блока
                                            cacheControllers[curQuery.number].getProcessor().addUnload(modelingParameters.memoryCycleTime);  
                                            
                                            //анулируем все его копии в других кэшах
                                            for (int k=0; k<cacheControllers.length; k++)
                                            {
                                                if (k == curQuery.number) continue;
                                                int replCol = cacheControllers[k].getCacheMemory().searchBlock(new BlockAddress(ba.moduleNumber, replTag));
                                                if (replCol != Const.NOT_FOUND) 
                                                {
                                                    cacheControllers[k].getCacheMemory().states[ba.moduleNumber][replCol] = 'i';
                                                }
                                            }
                                            //остальные процессоры также простаивают это время
                                            if (isWaiting)
                                            {
                                                for (int j=0; j<waitingProcessorsNumbers.length; j++)
                                                {
                                                    cacheControllers[j].getProcessor().addUnload(modelingParameters.memoryCycleTime);
                                                }
                                            }
                                        }
                                        else
                                        //вытесняемый блок не модифицирован, может стать эксклюзивным
                                        {
                                            //еще раз просматриваем все кэши, если вытесненный блок в них в единственном экземпляре
                                            //переводим его в состояние exclusive
                                            int replCount = 0;
                                            int replCol = 0;
                                            int lastReplCol = 0;
                                            int lastReplFound = 0;
                                            for (int k=0; k<cacheControllers.length; k++)
                                            {
                                                if (k == curQuery.number) continue;
                                                replCol = cacheControllers[k].getCacheMemory().searchBlock(new BlockAddress(ba.moduleNumber, replTag));
                                                if (replCol != Const.NOT_FOUND) 
                                                {
                                                   replCount++;
                                                   lastReplFound = k;
                                                   lastReplCol = replCol;
                                                }
                                            }
                                            if (replCount == 1)
                                            {                                                
                                                try
                                                {
                                                    cacheControllers[lastReplFound].getCacheMemory().states[ba.moduleNumber][lastReplCol] = 'e';                                            
                                                }
                                                catch (Exception ex)
                                                {
                                                    reportText.append(" --- !!! --- " + lastReplFound + " " + ba.moduleNumber + " " + replCol + "\n");                                                    
                                                }
                                            }
                                        }
                                        
                                        //записываем новый блок
                                        cacheControllers[curQuery.number].getCacheMemory().tags[ba.moduleNumber][replBlock] = ba.tag; 
                                        cacheControllers[curQuery.number].getCacheMemory().states[ba.moduleNumber][replBlock] = 'm'; 
                                        //увеличичваем счетчики простоя текущего и ожидающих процессоров
                                        //поиск указателя в кэше - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.searchCachePointerTime);
                                        //запись нового блока в кэш - шина занята
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.memoryCycleTime);
                                        //запись данных в кэш - шина свободна
                                        cacheControllers[curQuery.number].getProcessor().addUnload(
                                                modelingParameters.bufferOperationTime);
                                        //увеличиваем счетчики простоя ожидающих процессоров    
                                        if (isWaiting)
                                        {
                                            for (int i=0; i<waitingProcessorsNumbers.length; i++)
                                            {                                            
                                                if (!modifFound)
                                                {
                                                    cacheControllers[waitingProcessorsNumbers[i]].getProcessor().addUnload(
                                                         modelingParameters.memoryCycleTime);                                                                                    
                                                }
                                                else
                                                {
                                                    cacheControllers[i].getProcessor().addUnload(
                                                        modelingParameters.bufferOperationTime + 
                                                        modelingParameters.searchCachePointerTime);                                                
                                                }
                                            }           
                                        }
                                    }  
                                    //все остальные контроллеры ищут записываемый блок в своем кэше
                                    for (int i=0; i<cacheControllers.length; i++)
                                    {
                                        if (i == curQuery.number) continue;
                                        cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime);    
                                        //если в кэше какого-то процессора есть записываемый текущим процессором блок,
                                        //то этот блок переходит в состояние invalid
                                        int invNum;
                                        if ((invNum = cacheControllers[i].getCacheMemory().searchBlock(ba))!= Const.NOT_FOUND)
                                        {
                                            cacheControllers[i].getCacheMemory().states[ba.moduleNumber][invNum] = 'i'; 
                                            cacheControllers[i].getProcessor().addUnload(
                                             modelingParameters.searchCachePointerTime); 
                                        }                                        
                                    }                                                                                        
                                }
                                break;                                
                            }                            
                        }
                        break;            
                        
                    }   // обслуживание очередного запроса завершено

                    this.cacheControllers[curQuery.number].getProcessor().restart();                    
                    try
                    {
                        systemBusThread.sleep(5 * modelingParameters.systemBusDelay);
                    }
                    catch (Exception ex) {}
                }
            }
            //моделирование завершено - останавливаем поток шины
            else   
            {
                this.systemBusThread.stop();
                break;
            }
        }
    }    
    
    public double getSharedQueriesCount()
    {
        return (double)sharedQueries / allQueries * 100;
    }
    
    public int getEStatesCounter()
    {
        return this.eStateCounter;
    }
    
    public int getMStatesCounter()
    {
        return this.mStateCounter;
    }
    
    public int getSStatesCounter()
    {
        return this.sStateCounter;
    }
    
    public int getIStatesCounter()
    {
        return this.iStateCounter;
    }
    
    public void start()
    {
        systemBusThread.start();        
    }
}
