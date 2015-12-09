package cachemodeler_multyprocessor;

/**
 *
 * @author Taras
 */

import javax.swing.JTextArea;
import java.util.*;

//мультипроцессор
//включает в себя все моделируемые объекты:
//процессоры с кэш-памятью, системную шину и основную память
public class Multyprocessor implements Runnable
{    
    private Thread MultyprocThread; 
    private SystemParameters systemParameters; 
    private ModelingParameters modelingParameters;
    private Processor processors[];
    private CacheController cacheControllers[];
    private SystemBus systemBus;
    private ProcessorMonitor  processorMonitor;
    public boolean modelingRun;
    
    public Multyprocessor(
            SystemParameters systemParameters,
            ModelingParameters modelingParameters, JTextArea logText, JTextArea resText
            )
    {
        //задаем параметры системы и моделирования
        this.systemParameters = systemParameters;     
        this.modelingParameters = modelingParameters;
        modelingRun = false;
        //создаем процессоры
        processors = new Processor[systemParameters.processorsCount];
        int firstQueryAddress;
        for (int i=0; i<systemParameters.processorsCount; i++)
        {
            firstQueryAddress = (new Random()).nextInt(
                    (int)(systemParameters.memorySize - systemParameters.cacheBlockSize * modelingParameters.ShBlocksCount) / 2);
            processors[i] = new Processor(
                    modelingParameters, systemParameters, firstQueryAddress,
                    systemParameters.memorySize - 1, "proc_" + i, logText, i);            
        }
        //создаем контроллеры кэш-памяти и связываем их с процессорами
        //создаем системную шину и связываем ее с контроллерами
        cacheControllers = new CacheController[systemParameters.processorsCount];
        systemBus = new SystemBus(modelingParameters, systemParameters, logText);        
        for (int i=0; i<systemParameters.processorsCount; i++)
        {
            cacheControllers[i] = new CacheController(systemParameters, modelingParameters, logText);
            cacheControllers[i].setProcessor(processors[i]);
            cacheControllers[i].setSystemBus(systemBus);
            processors[i].setController(cacheControllers[i]);
        }      
        systemBus.setControllers(cacheControllers);
        //создаем монитор процессоров
        processorMonitor = new ProcessorMonitor(processors, this, resText);   
        MultyprocThread = new Thread(this, "multyproc_thread");       
    }
    
    public SystemBus getSystemBus()
    {
        return this.systemBus;
    }
    
    public void run()
    {
        for (int i=0; i<systemParameters.processorsCount; i++) 
        {
            processors[i].start();
            cacheControllers[i].start();
        }
        systemBus.start();
        processorMonitor.start(); 
        modelingRun = true;
    }
    
    
    
    //возвращает среднее время цикла памяти для каждого процессора и всей системы
    public double[] getAvgMemoryCycleTime()
    {        
        double cycleTimes[] = new double[systemParameters.processorsCount+1];
        double fullTime = 0;
        for (int i=0; i<systemParameters.processorsCount; i++)
        {
            double newCycleTime = cacheControllers[i].getAvrMemoryCycleTime();
            cycleTimes[i] = newCycleTime;
            fullTime += newCycleTime;
        }
        cycleTimes[systemParameters.processorsCount] = 
                fullTime / systemParameters.processorsCount;
        return cycleTimes;        
    }
    
    //возвращает загрузку для каждого процессора и всей системы
    public double[] getSystemLoading()
    {
        return processorMonitor.getLoadFactors();
    }
    
    //возвращает производительность системы
    public double getSystemPower()
    {        
        double power = 0;
        double[] loadings = this.getSystemLoading();
        for (int i=0; i<systemParameters.processorsCount; i++)
        {
            power += loadings[i] * 10;
        }
        return power;
    }
    
    
    public void startModeling()
    {
        MultyprocThread.run();
    }
    
    public double[] getHitRate()
    {
        double[] hitRates = new double[systemParameters.processorsCount+1];
        double fullHitRate = 0;
        for (int i=0; i<systemParameters.processorsCount; i++)
        {
            hitRates[i] = cacheControllers[i].getHitRate();
            fullHitRate += hitRates[i];
        }
        fullHitRate /= systemParameters.processorsCount;        
        hitRates[systemParameters.processorsCount] = fullHitRate;
        return hitRates;
    }
    
    public void stop()
    {     
        try
        {
            for (int i=0; i<systemParameters.processorsCount; i++)
            {
                processors[i].getProcessorThread().stop();
                cacheControllers[i].getCacheControllerThread().stop();
            }
            processorMonitor.processorMonitorThread.stop();
        }
        catch(Exception ex)
        {}
    }       
    
    public boolean isMESI()
    {
        if (this.systemParameters.coherenceProtocol == CoherenceProtocols.MESI)
        {
            return true;
        }
        else
        {
            return false;
        }
    }
    
    public boolean isMSI()
    {
        if (this.systemParameters.coherenceProtocol == CoherenceProtocols.MSI)
        {
            return true;
        }
        else
        {
            return false;
        }
    }
}
